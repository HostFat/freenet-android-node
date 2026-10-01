//! Local-radio contract courier.
//!
//! A nearby phone names one contract. This module reads it from the node on
//! this phone, or refuses. A contract the node does not already report as
//! present is never requested with `ContractRequest::Get` unless the caller
//! sets `allow_fetch`. That is the gate that keeps a missing contract off the
//! internet: the Freenet peer protocol is encrypted, so a UDP tunnel could not
//! see the request and stop it.

use std::fs;
use std::path::Path;
use std::time::Duration;

use freenet_stdlib::client_api::{
    ClientRequest, ContractRequest, ContractResponse, HostResponse, NodeDiagnosticsConfig,
    NodeQuery, QueryResponse, WebApi,
};
use freenet_stdlib::prelude::{
    CodeHash, ContractCode, ContractContainer, ContractInstanceId, ContractKey,
    ContractWasmAPIVersion, Parameters, RelatedContracts, WrappedContract, WrappedState,
};
use serde::Serialize;
use tokio_tungstenite::connect_async;

const PRESENCE_TIMEOUT: Duration = Duration::from_secs(15);
const LOCAL_GET_TIMEOUT: Duration = Duration::from_secs(20);
const FETCH_GET_TIMEOUT: Duration = Duration::from_secs(90);
const PUT_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_BLOB_BYTES: usize = 32 * 1024 * 1024;
const BLOB_MAGIC: &[u8; 4] = b"FNCT";

#[derive(Debug, PartialEq, Eq)]
enum NearbyPlan {
    /// Ask the node for a copy it already reports. This is the only `Get`
    /// issued when fetching is off.
    ReadLocal,
    /// The contract is not present and the caller allowed a download.
    Fetch,
    /// Present, but the caller does not want it sent. No `Get`.
    RefuseOwned,
    /// Not present and fetching is off. No `Get`.
    Absent,
}

fn nearby_plan(known: bool, allow_send: bool, allow_fetch: bool) -> NearbyPlan {
    if known && allow_send {
        NearbyPlan::ReadLocal
    } else if known {
        NearbyPlan::RefuseOwned
    } else if allow_fetch {
        NearbyPlan::Fetch
    } else {
        NearbyPlan::Absent
    }
}

/// A hosting-cache entry with no state and no subscription, while the node
/// already has peers, is the case where a client `Get` can leave the phone.
/// Skip that `Get` when fetching is off. A stored contract with a size, or any
/// contract while this node has no open connections, still uses the local read.
fn local_read_stays_on_device(
    open_connections: usize,
    subscribed: bool,
    subscribers: u32,
    size_bytes: u64,
) -> bool {
    open_connections == 0 || subscribed || subscribers > 0 || size_bytes > 0
}

#[derive(Serialize)]
struct ExportBody {
    status: &'static str,
    bytes: u64,
    path: String,
    message: String,
    fetched: bool,
}

pub(crate) fn export_contract(
    port: u16,
    key_hex: &str,
    output_directory: &str,
    allow_send: bool,
    allow_fetch: bool,
) -> String {
    let outcome = match decode_hex_32(key_hex) {
        Ok(bytes) => {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    return export_json(
                        "error",
                        0,
                        "",
                        format!("failed to start the courier runtime: {error}"),
                        false,
                    );
                }
            };
            runtime.block_on(export_contract_async(
                port,
                ContractInstanceId::new(bytes),
                key_hex.trim(),
                Path::new(output_directory),
                allow_send,
                allow_fetch,
            ))
        }
        Err(message) => Err(message),
    };
    match outcome {
        Ok(body) => serde_json::to_string(&body).unwrap_or_else(|_| {
            export_json(
                "error",
                0,
                "",
                "failed to encode the courier result".to_owned(),
                false,
            )
        }),
        Err(message) => export_json("error", 0, "", message, false),
    }
}

pub(crate) fn import_contract(port: u16, path: &str) -> String {
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            return import_json(
                "error",
                "",
                format!("failed to start the courier runtime: {error}"),
            );
        }
    };
    match runtime.block_on(import_contract_async(port, Path::new(path))) {
        Ok(key) => import_json("imported", &key, "Contract saved on this phone.".to_owned()),
        Err(message) => import_json("error", "", message),
    }
}

struct ReadContract {
    status: &'static str,
    fetched: bool,
    message: String,
    bytes: u64,
    blob: Vec<u8>,
}

async fn export_contract_async(
    port: u16,
    instance_id: ContractInstanceId,
    key_hex: &str,
    output_directory: &Path,
    allow_send: bool,
    allow_fetch: bool,
) -> Result<ExportBody, String> {
    let mut client = connect(port).await?;
    let presence = match presence(&mut client, instance_id).await {
        Ok(presence) => presence,
        Err(error) => {
            disconnect(&mut client).await;
            return Err(error);
        }
    };
    let plan = nearby_plan(presence.known, allow_send, allow_fetch);
    let outcome = match plan {
        NearbyPlan::ReadLocal
            if local_read_stays_on_device(
                presence.open_connections,
                presence.subscribed,
                presence.subscribers,
                presence.size_bytes,
            ) =>
        {
            read_contract(&mut client, instance_id, LOCAL_GET_TIMEOUT, false).await
        }
        NearbyPlan::ReadLocal => Ok(ReadContract {
            status: "unavailable",
            fetched: false,
            message: "This phone will not download a fresh copy of that contract.".to_owned(),
            bytes: 0,
            blob: Vec::new(),
        }),
        NearbyPlan::Fetch => read_contract(&mut client, instance_id, FETCH_GET_TIMEOUT, true).await,
        NearbyPlan::RefuseOwned => Ok(ReadContract {
            status: "refused",
            fetched: false,
            message: "Sending contracts already on this phone is off.".to_owned(),
            bytes: 0,
            blob: Vec::new(),
        }),
        NearbyPlan::Absent => Ok(ReadContract {
            status: "absent",
            fetched: false,
            message: "This phone does not have that contract and will not download it.".to_owned(),
            bytes: 0,
            blob: Vec::new(),
        }),
    };
    disconnect(&mut client).await;
    let read = outcome?;
    if read.blob.is_empty() {
        return Ok(ExportBody {
            status: read.status,
            bytes: read.bytes,
            path: String::new(),
            message: read.message,
            fetched: read.fetched,
        });
    }
    let bytes = read.blob.len() as u64;
    fs::create_dir_all(output_directory)
        .map_err(|error| format!("failed to create the nearby directory: {error}"))?;
    let path = output_directory.join(format!("nearby-{key_hex}.bin"));
    fs::write(&path, &read.blob)
        .map_err(|error| format!("failed to write the nearby contract: {error}"))?;
    Ok(ExportBody {
        status: read.status,
        bytes,
        path: path.display().to_string(),
        message: read.message,
        fetched: read.fetched,
    })
}

struct Presence {
    known: bool,
    subscribed: bool,
    subscribers: u32,
    size_bytes: u64,
    open_connections: usize,
}

async fn presence(
    client: &mut WebApi,
    instance_id: ContractInstanceId,
) -> Result<Presence, String> {
    let probe = ContractKey::from_id_and_code(instance_id, CodeHash::new([0u8; 32]));
    let probe_label = probe.to_string();
    client
        .send(ClientRequest::NodeQueries(NodeQuery::NodeDiagnostics {
            config: NodeDiagnosticsConfig {
                include_node_info: false,
                include_network_info: true,
                include_subscriptions: true,
                contract_keys: vec![probe],
                include_system_metrics: false,
                include_detailed_peer_info: false,
                include_subscriber_peer_ids: false,
            },
        }))
        .await
        .map_err(|error| format!("failed to ask whether the contract is stored here: {error}"))?;
    let response = recv(client, PRESENCE_TIMEOUT, "presence").await?;
    let HostResponse::QueryResponse(QueryResponse::NodeDiagnostics(info)) = response else {
        return Err("the node did not answer the local contract check".to_owned());
    };
    let labels = [instance_id.to_string(), probe_label];
    let state = labels
        .iter()
        .find_map(|label| info.contract_states.get(label));
    let subscribed = info
        .subscriptions
        .iter()
        .any(|subscription| subscription.contract_key == instance_id);
    let known = state.is_some() || subscribed;
    Ok(Presence {
        known,
        subscribed,
        subscribers: state.map(|state| state.subscribers).unwrap_or(0),
        size_bytes: state.map(|state| state.size_bytes).unwrap_or(0),
        open_connections: info
            .network_info
            .as_ref()
            .map(|network| network.active_connections)
            .unwrap_or(usize::MAX),
    })
}

async fn read_contract(
    client: &mut WebApi,
    instance_id: ContractInstanceId,
    timeout: Duration,
    fetched: bool,
) -> Result<ReadContract, String> {
    client
        .send(ClientRequest::ContractOp(ContractRequest::Get {
            key: instance_id,
            return_contract_code: true,
            subscribe: false,
            blocking_subscribe: false,
        }))
        .await
        .map_err(|error| format!("failed to request the contract from this phone: {error}"))?;
    let response = recv(client, timeout, "get").await?;
    match response {
        HostResponse::ContractResponse(ContractResponse::GetResponse {
            state, contract, ..
        }) => {
            let Some(ContractContainer::Wasm(ContractWasmAPIVersion::V1(wrapped))) = contract
            else {
                return Err("the node did not return the contract code".to_owned());
            };
            let code = wrapped.code().data();
            let params = wrapped.params();
            let state_bytes = state.as_ref();
            let raw_bytes = code
                .len()
                .saturating_add(params.as_ref().len())
                .saturating_add(state_bytes.len()) as u64;
            if raw_bytes > MAX_BLOB_BYTES as u64 {
                return Ok(ReadContract {
                    status: "too_large",
                    fetched,
                    message: "That contract is larger than 32 MiB.".to_owned(),
                    bytes: raw_bytes,
                    blob: Vec::new(),
                });
            }
            let blob = encode_blob(code, params.as_ref(), state_bytes)?;
            Ok(ReadContract {
                status: if fetched { "fetched" } else { "local" },
                fetched,
                message: String::new(),
                bytes: blob.len() as u64,
                blob,
            })
        }
        HostResponse::ContractResponse(ContractResponse::NotFound { .. }) => Ok(ReadContract {
            status: "absent",
            fetched: false,
            message: "The node does not have that contract.".to_owned(),
            bytes: 0,
            blob: Vec::new(),
        }),
        HostResponse::ContractResponse(other) => {
            Err(format!("unexpected contract response: {other:?}"))
        }
        other => Err(format!("unexpected response: {other:?}")),
    }
}

async fn import_contract_async(port: u16, path: &Path) -> Result<String, String> {
    let bytes =
        fs::read(path).map_err(|error| format!("failed to read the nearby contract: {error}"))?;
    if bytes.len() > MAX_BLOB_BYTES {
        return Err("that contract is larger than 32 MiB".to_owned());
    }
    let (code, params, state) = decode_blob(&bytes)?;
    if !code.starts_with(b"\0asm") {
        return Err("the nearby payload is not a WebAssembly contract".to_owned());
    }
    let wrapped = WrappedContract::new(
        std::sync::Arc::new(ContractCode::from(code)),
        Parameters::from(params),
    );
    let contract = ContractContainer::Wasm(ContractWasmAPIVersion::V1(wrapped));
    let mut client = connect(port).await?;
    let sent = client
        .send(ClientRequest::ContractOp(ContractRequest::Put {
            contract,
            state: WrappedState::new(state),
            related_contracts: RelatedContracts::default(),
            subscribe: false,
            blocking_subscribe: false,
        }))
        .await;
    if let Err(error) = sent {
        disconnect(&mut client).await;
        return Err(format!(
            "failed to save the contract on this phone: {error}"
        ));
    }
    let response = recv(&mut client, PUT_TIMEOUT, "put").await;
    disconnect(&mut client).await;
    match response? {
        HostResponse::ContractResponse(ContractResponse::PutResponse { key }) => {
            Ok(key.to_string())
        }
        other => Err(format!("the node did not accept the contract: {other:?}")),
    }
}

fn encode_blob(code: &[u8], params: &[u8], state: &[u8]) -> Result<Vec<u8>, String> {
    let total = 17usize
        .saturating_add(code.len())
        .saturating_add(params.len())
        .saturating_add(state.len());
    if total > MAX_BLOB_BYTES {
        return Err("contract is larger than 32 MiB".to_owned());
    }
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(BLOB_MAGIC);
    out.push(1);
    push_u32(&mut out, code.len())?;
    out.extend_from_slice(code);
    push_u32(&mut out, params.len())?;
    out.extend_from_slice(params);
    push_u32(&mut out, state.len())?;
    out.extend_from_slice(state);
    Ok(out)
}

fn decode_blob(bytes: &[u8]) -> Result<(Vec<u8>, Vec<u8>, Vec<u8>), String> {
    if bytes.len() < 17 || &bytes[0..4] != BLOB_MAGIC || bytes[4] != 1 {
        return Err("the nearby payload is not a contract from this app".to_owned());
    }
    let mut offset = 5;
    let code = take_chunk(bytes, &mut offset)?;
    let params = take_chunk(bytes, &mut offset)?;
    let state = take_chunk(bytes, &mut offset)?;
    if offset != bytes.len() {
        return Err("the nearby payload has trailing bytes".to_owned());
    }
    Ok((code, params, state))
}

fn take_chunk(bytes: &[u8], offset: &mut usize) -> Result<Vec<u8>, String> {
    if *offset + 4 > bytes.len() {
        return Err("the nearby payload is truncated".to_owned());
    }
    let len = u32::from_be_bytes(bytes[*offset..*offset + 4].try_into().expect("4 bytes")) as usize;
    *offset += 4;
    if *offset + len > bytes.len() {
        return Err("the nearby payload is truncated".to_owned());
    }
    let chunk = bytes[*offset..*offset + len].to_vec();
    *offset += len;
    Ok(chunk)
}

fn push_u32(out: &mut Vec<u8>, len: usize) -> Result<(), String> {
    let len = u32::try_from(len).map_err(|_| "a contract field is too large".to_owned())?;
    out.extend_from_slice(&len.to_be_bytes());
    Ok(())
}

fn decode_hex_32(text: &str) -> Result<[u8; 32], String> {
    let text = text.trim();
    if text.len() != 64 {
        return Err("contract key must be 32 bytes".to_owned());
    }
    let mut out = [0u8; 32];
    for (index, byte) in out.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&text[index * 2..index * 2 + 2], 16)
            .map_err(|_| "contract key must be hexadecimal".to_owned())?;
    }
    Ok(out)
}

fn export_json(
    status: &'static str,
    bytes: u64,
    path: &str,
    message: String,
    fetched: bool,
) -> String {
    serde_json::to_string(&ExportBody {
        status,
        bytes,
        path: path.to_owned(),
        message,
        fetched,
    })
    .unwrap_or_else(|_| {
        "{\"status\":\"error\",\"bytes\":0,\"path\":\"\",\"message\":\"encode failed\",\"fetched\":false}"
            .to_owned()
    })
}

fn import_json(status: &str, key: &str, message: String) -> String {
    serde_json::json!({
        "status": status,
        "key": key,
        "message": message,
    })
    .to_string()
}

async fn connect(port: u16) -> Result<WebApi, String> {
    let url = format!("ws://127.0.0.1:{port}/v1/contract/command?encodingProtocol=native");
    let connection = tokio::time::timeout(PRESENCE_TIMEOUT, connect_async(&url))
        .await
        .map_err(|_| "timed out connecting to the node on this phone".to_owned())?
        .map_err(|error| format!("the node on this phone is not accepting clients: {error}"))?;
    Ok(WebApi::start(connection.0))
}

async fn recv(client: &mut WebApi, timeout: Duration, what: &str) -> Result<HostResponse, String> {
    tokio::time::timeout(timeout, client.recv())
        .await
        .map_err(|_| format!("timed out waiting for the node {what} response"))?
        .map_err(|error| format!("the node {what} response failed: {error}"))
}

async fn disconnect(client: &mut WebApi) {
    let _ = client.send(ClientRequest::Disconnect { cause: None }).await;
}

#[cfg(test)]
mod tests {
    use super::{
        NearbyPlan, decode_blob, decode_hex_32, encode_blob, local_read_stays_on_device,
        nearby_plan,
    };

    #[test]
    fn missing_contract_is_not_fetched_when_download_is_off() {
        assert_eq!(nearby_plan(false, false, false), NearbyPlan::Absent);
        assert_eq!(nearby_plan(false, true, false), NearbyPlan::Absent);
    }

    #[test]
    fn missing_contract_is_fetched_only_when_download_is_on() {
        assert_eq!(nearby_plan(false, false, true), NearbyPlan::Fetch);
        assert_eq!(nearby_plan(false, true, true), NearbyPlan::Fetch);
    }

    #[test]
    fn stored_contract_is_read_only_when_sending_is_on() {
        assert_eq!(nearby_plan(true, true, false), NearbyPlan::ReadLocal);
        assert_eq!(nearby_plan(true, true, true), NearbyPlan::ReadLocal);
        assert_eq!(nearby_plan(true, false, true), NearbyPlan::RefuseOwned);
        assert_eq!(nearby_plan(true, false, false), NearbyPlan::RefuseOwned);
    }

    #[test]
    fn empty_hosting_record_does_not_go_out_while_peers_are_connected() {
        assert!(!local_read_stays_on_device(2, false, 0, 0));
        assert!(local_read_stays_on_device(0, false, 0, 0));
        assert!(local_read_stays_on_device(2, true, 0, 0));
        assert!(local_read_stays_on_device(2, false, 1, 0));
        assert!(local_read_stays_on_device(2, false, 0, 40));
    }

    #[test]
    fn contract_blob_round_trips_and_rejects_a_truncated_payload() {
        let code = b"\0asmrest";
        let blob = encode_blob(code, b"params", b"state").expect("blob");
        let (got_code, got_params, got_state) = decode_blob(&blob).expect("decode");
        assert_eq!(got_code, code);
        assert_eq!(got_params, b"params");
        assert_eq!(got_state, b"state");
        assert!(decode_blob(&blob[..blob.len() - 1]).is_err());
        assert!(decode_hex_32("aa").is_err());
        assert_eq!(decode_hex_32(&"ab".repeat(32)).unwrap()[0], 0xab);
    }
}

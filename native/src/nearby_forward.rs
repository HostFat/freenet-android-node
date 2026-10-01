//! Live nearby updates and applying an update that arrived over the radio.
//!
//! Subscribing here is limited to contracts this node already lists. A
//! subscribe for a contract that is not present can make the node fetch it,
//! which would ignore the download switch.

use std::collections::{HashSet, VecDeque};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use freenet_stdlib::client_api::{
    ClientRequest, ContractRequest, ContractResponse, HostResponse, NodeDiagnosticsConfig,
    NodeQuery, QueryResponse,
};
use freenet_stdlib::prelude::{ContractInstanceId, ContractKey, State, StateDelta, UpdateData};

use crate::nearby_contract::{
    MAX_BLOB_BYTES, connect, decode_hex_32, disconnect, local_read_stays_on_device, presence, recv,
};

const UPDATE_TIMEOUT: Duration = Duration::from_secs(30);
const QUEUE_LIMIT: usize = 8;

struct PendingUpdate {
    key_hex: String,
    path: String,
    bytes: u64,
}

struct Watch {
    enabled: AtomicBool,
    port: AtomicU16,
    directory: Mutex<String>,
    extra: Mutex<HashSet<[u8; 32]>>,
    queue: Mutex<VecDeque<PendingUpdate>>,
    cv: Condvar,
    started: AtomicBool,
}

static WATCH: OnceLock<Watch> = OnceLock::new();

fn watch() -> &'static Watch {
    WATCH.get_or_init(|| Watch {
        enabled: AtomicBool::new(false),
        port: AtomicU16::new(0),
        directory: Mutex::new(String::new()),
        extra: Mutex::new(HashSet::new()),
        queue: Mutex::new(VecDeque::new()),
        cv: Condvar::new(),
        started: AtomicBool::new(false),
    })
}

pub(crate) fn watch_start(port: u16, directory: &str) {
    let state = watch();
    *state.directory.lock().expect("nearby watch directory") = directory.to_owned();
    state.port.store(port, Ordering::Relaxed);
    state.enabled.store(true, Ordering::Relaxed);
    if state.started.swap(true, Ordering::AcqRel) {
        return;
    }
    let spawned = thread::Builder::new()
        .name("freenet-nearby-watch".to_owned())
        .spawn(|| {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(_) => {
                    watch().started.store(false, Ordering::Relaxed);
                    return;
                }
            };
            runtime.block_on(run_watch());
        });
    if spawned.is_err() {
        state.started.store(false, Ordering::Relaxed);
    }
}

pub(crate) fn watch_stop() {
    let Some(watch) = WATCH.get() else {
        return;
    };
    watch.enabled.store(false, Ordering::Relaxed);
    watch.queue.lock().expect("nearby watch queue").clear();
    watch.cv.notify_all();
}

pub(crate) fn watch_add_key(key_hex: &str) {
    let Ok(bytes) = decode_hex_32(key_hex) else {
        return;
    };
    watch()
        .extra
        .lock()
        .expect("nearby watch keys")
        .insert(bytes);
}

pub(crate) fn watch_poll(timeout_ms: u64) -> String {
    let watch = watch();
    let guard = watch.queue.lock().expect("nearby watch queue");
    let (mut guard, _) = watch
        .cv
        .wait_timeout_while(guard, Duration::from_millis(timeout_ms), |queue| {
            queue.is_empty()
        })
        .expect("nearby watch queue");
    if let Some(update) = guard.pop_front() {
        return serde_json::json!({
            "status": "update",
            "key": update.key_hex,
            "path": update.path,
            "bytes": update.bytes,
        })
        .to_string();
    }
    if !watch.enabled.load(Ordering::Relaxed) {
        return serde_json::json!({"status": "stopped"}).to_string();
    }
    serde_json::json!({"status": "timeout"}).to_string()
}

fn enqueue(key: &[u8; 32], bytes: &[u8]) {
    let watch = watch();
    if !watch.enabled.load(Ordering::Relaxed) || bytes.len() > MAX_BLOB_BYTES {
        return;
    }
    let directory = watch
        .directory
        .lock()
        .expect("nearby watch directory")
        .clone();
    if directory.is_empty() {
        return;
    }
    if fs::create_dir_all(&directory).is_err() {
        return;
    }
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let path = PathBuf::from(&directory).join(format!("nearby-upd-{nanos}.bin"));
    if fs::write(&path, bytes).is_err() {
        return;
    }
    let mut queue = watch.queue.lock().expect("nearby watch queue");
    while queue.len() >= QUEUE_LIMIT {
        if let Some(old) = queue.pop_front() {
            let _ = fs::remove_file(old.path);
        }
    }
    queue.push_back(PendingUpdate {
        key_hex: hex_encode(key),
        path: path.display().to_string(),
        bytes: bytes.len() as u64,
    });
    watch.cv.notify_one();
}

async fn run_watch() {
    let mut subscribed: HashSet<[u8; 32]> = HashSet::new();
    loop {
        let watch = watch();
        if !watch.enabled.load(Ordering::Relaxed) {
            subscribed.clear();
            tokio::time::sleep(Duration::from_millis(200)).await;
            continue;
        }
        let port = watch.port.load(Ordering::Relaxed);
        let mut client = match connect(port).await {
            Ok(client) => client,
            Err(_) => {
                tokio::time::sleep(Duration::from_secs(2)).await;
                continue;
            }
        };
        let mut next_refresh = tokio::time::Instant::now();
        loop {
            if !watch.enabled.load(Ordering::Relaxed) {
                break;
            }
            if tokio::time::Instant::now() >= next_refresh {
                if refresh_subscriptions(&mut client, &mut subscribed)
                    .await
                    .is_err()
                {
                    break;
                }
                next_refresh = tokio::time::Instant::now() + Duration::from_secs(20);
            }
            match tokio::time::timeout(Duration::from_secs(1), client.recv()).await {
                Ok(Ok(HostResponse::ContractResponse(ContractResponse::UpdateNotification {
                    key,
                    update,
                }))) => {
                    if let Ok(bytes) = encode_update_data(&update) {
                        if let Some(id) = instance_bytes(key.id()) {
                            enqueue(&id, &bytes);
                        }
                    }
                }
                Ok(Ok(_)) => {}
                Ok(Err(_)) => break,
                Err(_) => {}
            }
        }
        disconnect(&mut client).await;
    }
}

async fn refresh_subscriptions(
    client: &mut freenet_stdlib::client_api::WebApi,
    subscribed: &mut HashSet<[u8; 32]>,
) -> Result<(), String> {
    let wanted = wanted_keys(client).await?;
    for id in wanted {
        if !subscribed.insert(id) {
            continue;
        }
        let sent = client
            .send(ClientRequest::ContractOp(ContractRequest::Subscribe {
                key: ContractInstanceId::new(id),
                summary: None,
            }))
            .await;
        if sent.is_err() {
            subscribed.remove(&id);
            return Err("subscribe failed".to_owned());
        }
    }
    Ok(())
}

async fn wanted_keys(
    client: &mut freenet_stdlib::client_api::WebApi,
) -> Result<Vec<[u8; 32]>, String> {
    client
        .send(ClientRequest::NodeQueries(NodeQuery::NodeDiagnostics {
            config: NodeDiagnosticsConfig {
                include_node_info: false,
                include_network_info: false,
                include_subscriptions: true,
                contract_keys: Vec::new(),
                include_system_metrics: false,
                include_detailed_peer_info: false,
                include_subscriber_peer_ids: false,
            },
        }))
        .await
        .map_err(|error| format!("failed to list subscriptions: {error}"))?;
    let info = loop {
        let response = recv(client, Duration::from_secs(15), "subscriptions").await?;
        match response {
            HostResponse::QueryResponse(QueryResponse::NodeDiagnostics(info)) => break info,
            HostResponse::ContractResponse(ContractResponse::UpdateNotification {
                key,
                update,
            }) => {
                if let Ok(bytes) = encode_update_data(&update) {
                    if let Some(id) = instance_bytes(key.id()) {
                        enqueue(&id, &bytes);
                    }
                }
            }
            _ => {}
        }
    };
    let mut keys = Vec::new();
    for subscription in &info.subscriptions {
        if let Some(bytes) = instance_bytes(&subscription.contract_key) {
            keys.push(bytes);
        }
    }
    let extra = watch().extra.lock().expect("nearby watch keys").clone();
    for id in extra {
        keys.push(id);
    }
    Ok(keys)
}

fn instance_bytes(id: &ContractInstanceId) -> Option<[u8; 32]> {
    let bytes = id.as_bytes();
    if bytes.len() != 32 {
        return None;
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(bytes);
    Some(out)
}

pub(crate) fn apply_update(port: u16, key_hex: &str, path: &str) -> String {
    let outcome = (|| {
        let id = decode_hex_32(key_hex)?;
        let bytes =
            fs::read(path).map_err(|error| format!("failed to read the nearby update: {error}"))?;
        if bytes.len() > MAX_BLOB_BYTES {
            return Err("that update is larger than 128 MiB".to_owned());
        }
        let update = decode_update_data(&bytes)?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| format!("failed to start the update runtime: {error}"))?;
        runtime.block_on(apply_update_async(port, id, update))
    })();
    match outcome {
        Ok(status) => serde_json::json!({ "status": status, "message": "" }).to_string(),
        Err(message) => serde_json::json!({ "status": "error", "message": message }).to_string(),
    }
}

async fn apply_update_async(
    port: u16,
    id: [u8; 32],
    update: UpdateData<'static>,
) -> Result<&'static str, String> {
    let instance_id = ContractInstanceId::new(id);
    let mut client = connect(port).await?;
    let present = presence(&mut client, instance_id).await;
    let present = match present {
        Ok(present) => present,
        Err(error) => {
            disconnect(&mut client).await;
            return Err(error);
        }
    };
    if !present.known
        || !local_read_stays_on_device(
            present.open_connections,
            present.subscribed,
            present.subscribers,
            present.size_bytes,
        )
    {
        disconnect(&mut client).await;
        return Ok("missing");
    }
    let sent = client
        .send(ClientRequest::ContractOp(ContractRequest::Update {
            key: ContractKey::from_id_and_code(
                instance_id,
                freenet_stdlib::prelude::CodeHash::new([0u8; 32]),
            ),
            data: update,
        }))
        .await;
    if let Err(error) = sent {
        disconnect(&mut client).await;
        return Err(format!("failed to apply the nearby update: {error}"));
    }
    let response = recv(&mut client, UPDATE_TIMEOUT, "update").await;
    disconnect(&mut client).await;
    match response? {
        HostResponse::ContractResponse(ContractResponse::UpdateResponse { .. }) => Ok("applied"),
        HostResponse::ContractResponse(ContractResponse::UpdateNotification { .. }) => {
            Ok("applied")
        }
        other => Err(format!("the node did not apply the update: {other:?}")),
    }
}

pub(crate) fn encode_update_data(update: &UpdateData<'_>) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    out.push(1);
    match update {
        UpdateData::State(state) => {
            out.push(1);
            push_chunk(&mut out, state.as_ref())?;
        }
        UpdateData::Delta(delta) => {
            out.push(2);
            push_chunk(&mut out, delta.as_ref())?;
        }
        UpdateData::StateAndDelta { state, delta } => {
            out.push(3);
            push_chunk(&mut out, state.as_ref())?;
            push_chunk(&mut out, delta.as_ref())?;
        }
        UpdateData::RelatedState { related_to, state } => {
            out.push(4);
            out.extend_from_slice(related_to.as_bytes());
            push_chunk(&mut out, state.as_ref())?;
        }
        UpdateData::RelatedDelta { related_to, delta } => {
            out.push(5);
            out.extend_from_slice(related_to.as_bytes());
            push_chunk(&mut out, delta.as_ref())?;
        }
        UpdateData::RelatedStateAndDelta {
            related_to,
            state,
            delta,
        } => {
            out.push(6);
            out.extend_from_slice(related_to.as_bytes());
            push_chunk(&mut out, state.as_ref())?;
            push_chunk(&mut out, delta.as_ref())?;
        }
        _ => return Err("this update shape cannot be forwarded".to_owned()),
    }
    Ok(out)
}

pub(crate) fn decode_update_data(bytes: &[u8]) -> Result<UpdateData<'static>, String> {
    if bytes.len() < 2 || bytes[0] != 1 {
        return Err("the nearby update is not from this app".to_owned());
    }
    let mut offset = 2;
    match bytes[1] {
        1 => Ok(UpdateData::State(State::from(take_chunk(
            bytes,
            &mut offset,
        )?))),
        2 => Ok(UpdateData::Delta(StateDelta::from(take_chunk(
            bytes,
            &mut offset,
        )?))),
        3 => {
            let state = State::from(take_chunk(bytes, &mut offset)?);
            let delta = StateDelta::from(take_chunk(bytes, &mut offset)?);
            Ok(UpdateData::StateAndDelta { state, delta })
        }
        4 => {
            let related_to = take_id(bytes, &mut offset)?;
            let state = State::from(take_chunk(bytes, &mut offset)?);
            Ok(UpdateData::RelatedState { related_to, state })
        }
        5 => {
            let related_to = take_id(bytes, &mut offset)?;
            let delta = StateDelta::from(take_chunk(bytes, &mut offset)?);
            Ok(UpdateData::RelatedDelta { related_to, delta })
        }
        6 => {
            let related_to = take_id(bytes, &mut offset)?;
            let state = State::from(take_chunk(bytes, &mut offset)?);
            let delta = StateDelta::from(take_chunk(bytes, &mut offset)?);
            Ok(UpdateData::RelatedStateAndDelta {
                related_to,
                state,
                delta,
            })
        }
        _ => Err("the nearby update shape is unknown".to_owned()),
    }
}

fn take_id(bytes: &[u8], offset: &mut usize) -> Result<ContractInstanceId, String> {
    if *offset + 32 > bytes.len() {
        return Err("the nearby update is truncated".to_owned());
    }
    let mut id = [0u8; 32];
    id.copy_from_slice(&bytes[*offset..*offset + 32]);
    *offset += 32;
    Ok(ContractInstanceId::new(id))
}

fn take_chunk(bytes: &[u8], offset: &mut usize) -> Result<Vec<u8>, String> {
    if *offset + 4 > bytes.len() {
        return Err("the nearby update is truncated".to_owned());
    }
    let len = u32::from_be_bytes(bytes[*offset..*offset + 4].try_into().expect("4 bytes")) as usize;
    *offset += 4;
    if *offset + len > bytes.len() {
        return Err("the nearby update is truncated".to_owned());
    }
    let chunk = bytes[*offset..*offset + len].to_vec();
    *offset += len;
    Ok(chunk)
}

fn push_chunk(out: &mut Vec<u8>, bytes: &[u8]) -> Result<(), String> {
    let len = u32::try_from(bytes.len()).map_err(|_| "an update field is too large".to_owned())?;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(bytes);
    Ok(())
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::{decode_update_data, encode_update_data};
    use freenet_stdlib::prelude::{State, UpdateData};

    #[test]
    fn state_update_round_trips() {
        let update = UpdateData::State(State::from(b"room".to_vec()));
        let bytes = encode_update_data(&update).expect("encode");
        let decoded = decode_update_data(&bytes).expect("decode");
        assert_eq!(decoded, update);
    }
}

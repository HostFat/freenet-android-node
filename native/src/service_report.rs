//! Live node diagnostics for the same payload as `freenet service report`.

use std::time::Duration;

use freenet_stdlib::client_api::{
    ClientRequest, HostResponse, NodeDiagnosticsConfig, NodeQuery, QueryResponse, WebApi,
};
use tokio_tungstenite::connect_async;

const WS_TIMEOUT: Duration = Duration::from_secs(15);

pub(crate) fn query_node_diagnostics(port: u16) -> Result<String, String> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("failed to create tokio runtime: {error}"))?;
    rt.block_on(async {
        match tokio::time::timeout(WS_TIMEOUT, query_once("127.0.0.1", port)).await {
            Ok(result) => result,
            Err(_) => Err(format!("127.0.0.1:{port}: timed out after {WS_TIMEOUT:?}")),
        }
    })
}

async fn query_once(host: &str, port: u16) -> Result<String, String> {
    let url = format!("ws://{host}:{port}/v1/contract/command?encodingProtocol=native");
    let (stream, _) = connect_async(&url)
        .await
        .map_err(|error| format!("{host}:{port}: Failed to connect to node WebSocket API: {error}"))?;
    let mut client = WebApi::start(stream);
    let config = NodeDiagnosticsConfig {
        include_node_info: true,
        include_network_info: true,
        include_subscriptions: true,
        contract_keys: vec![],
        include_system_metrics: true,
        include_detailed_peer_info: true,
        include_subscriber_peer_ids: false,
    };
    client
        .send(ClientRequest::NodeQueries(NodeQuery::NodeDiagnostics {
            config,
        }))
        .await
        .map_err(|error| format!("{host}:{port}: Failed to send diagnostics query: {error}"))?;
    let response = client
        .recv()
        .await
        .map_err(|error| format!("{host}:{port}: Failed to receive diagnostics response: {error}"))?;
    let _ = client.send(ClientRequest::Disconnect { cause: None }).await;
    match response {
        HostResponse::QueryResponse(QueryResponse::NodeDiagnostics(diag)) => {
            serde_json::to_string_pretty(&diag).map_err(|error| {
                format!("{host}:{port}: Failed to serialize diagnostics: {error}")
            })
        }
        _ => Err(format!("{host}:{port}: Unexpected response from node")),
    }
}

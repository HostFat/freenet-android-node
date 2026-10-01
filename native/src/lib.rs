use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};

mod contract_proof;
mod nearby_contract;
mod nearby_forward;
mod runtime;
mod service_report;

use runtime::{jni_error_response, node_runtime, success_response};

const BRIDGE_VERSION: &str = env!("CARGO_PKG_VERSION");
const FREENET_CORE_VERSION: &str = env!("FREENET_CORE_VERSION");

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativePing(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_string(&mut env, || "pong".to_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeBuildInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_string(&mut env, || {
        format!(
            "Rust JNI bridge {BRIDGE_VERSION}, {}",
            android_target_name()
        )
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeFreenetBuildInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_string(&mut env, freenet_build_info)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeStartLocalNode(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&config_json) {
        Ok(config) => node_runtime().start_local(&config.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONFIG",
            format!("Failed to read configJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeStartNetworkNode(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&config_json) {
        Ok(config) => node_runtime().start_network(&config.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONFIG",
            format!("Failed to read configJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeUpdateConnectivity(
    mut env: JNIEnv,
    _class: JClass,
    connectivity_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&connectivity_json) {
        Ok(connectivity) => node_runtime().update_connectivity(&connectivity.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONNECTIVITY",
            format!("Failed to read connectivityJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeStopNode(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().stop())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetNodeStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().status())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetRecentLogs(
    mut env: JNIEnv,
    _class: JClass,
    max_entries: jint,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().recent_logs(max_entries))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetStorageStatus(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&config_json) {
        Ok(config) => node_runtime().storage_status(&config.to_string_lossy()),
        Err(error) => jni_error_response(
            "INVALID_CONFIG",
            format!("Failed to read configJson from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeRunContractProof(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().run_contract_proof())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeVerifyContractPersistence(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().verify_contract_persistence())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeGetContractProofStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jni_response(&mut env, |_| node_runtime().contract_proof_status())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeAppendInfoLog(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
) -> jstring {
    jni_response(&mut env, |env| match env.get_string(&message) {
        Ok(text) => {
            node_runtime().append_info_log(&text.to_string_lossy());
            success_response("logged")
        }
        Err(error) => jni_error_response(
            "INVALID_ARGUMENT",
            format!("Failed to read the log message from JNI: {error}"),
        ),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeQueryNodeDiagnostics(
    mut env: JNIEnv,
    _class: JClass,
    websocket_port: jint,
) -> jstring {
    jni_response(&mut env, |_| {
        if websocket_port <= 0 || websocket_port > 65535 {
            return jni_error_response(
                "INVALID_ARGUMENT",
                "websocketPort must be between 1 and 65535",
            );
        }
        match service_report::query_node_diagnostics(websocket_port as u16) {
            Ok(diagnostics) => match serde_json::from_str::<serde_json::Value>(&diagnostics) {
                Ok(value) => success_response(value),
                Err(_) => success_response(diagnostics),
            },
            Err(error) => jni_error_response("NODE_DIAGNOSTICS_UNAVAILABLE", error),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyExportContract(
    mut env: JNIEnv,
    _class: JClass,
    websocket_port: jint,
    contract_key_hex: JString,
    output_directory: JString,
    allow_send_owned: jboolean,
    allow_fetch_missing: jboolean,
) -> jstring {
    jni_response(&mut env, |env| {
        let key = match env.get_string(&contract_key_hex) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(error) => {
                return nearby_error("failed to read the contract key", &error.to_string(), true);
            }
        };
        let directory = match env.get_string(&output_directory) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(error) => {
                return nearby_error(
                    "failed to read the output directory",
                    &error.to_string(),
                    true,
                );
            }
        };
        if websocket_port <= 0 || websocket_port > 65535 {
            return nearby_error("websocketPort must be between 1 and 65535", "", true);
        }
        nearby_contract::export_contract(
            websocket_port as u16,
            &key,
            &directory,
            allow_send_owned != 0,
            allow_fetch_missing != 0,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyImportContract(
    mut env: JNIEnv,
    _class: JClass,
    websocket_port: jint,
    contract_file_path: JString,
) -> jstring {
    jni_response(&mut env, |env| {
        let path = match env.get_string(&contract_file_path) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(error) => {
                return nearby_error(
                    "failed to read the contract path",
                    &error.to_string(),
                    false,
                );
            }
        };
        if websocket_port <= 0 || websocket_port > 65535 {
            return nearby_error("websocketPort must be between 1 and 65535", "", false);
        }
        nearby_contract::import_contract(websocket_port as u16, &path)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyApplyUpdate(
    mut env: JNIEnv,
    _class: JClass,
    websocket_port: jint,
    contract_key_hex: JString,
    update_file_path: JString,
) -> jstring {
    jni_response(&mut env, |env| {
        let key = match env.get_string(&contract_key_hex) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(error) => {
                return serde_json::json!({
                    "status": "error",
                    "message": format!("failed to read the contract key: {error}"),
                })
                .to_string();
            }
        };
        let path = match env.get_string(&update_file_path) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(error) => {
                return serde_json::json!({
                    "status": "error",
                    "message": format!("failed to read the update path: {error}"),
                })
                .to_string();
            }
        };
        if websocket_port <= 0 || websocket_port > 65535 {
            return serde_json::json!({
                "status": "error",
                "message": "websocketPort must be between 1 and 65535",
            })
            .to_string();
        }
        nearby_forward::apply_update(websocket_port as u16, &key, &path)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyWatchStart(
    mut env: JNIEnv,
    _class: JClass,
    websocket_port: jint,
    output_directory: JString,
) {
    if websocket_port <= 0 || websocket_port > 65535 {
        return;
    }
    let Ok(directory) = env.get_string(&output_directory) else {
        return;
    };
    nearby_forward::watch_start(websocket_port as u16, &directory.to_string_lossy());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyWatchStop(
    _env: JNIEnv,
    _class: JClass,
) {
    nearby_forward::watch_stop();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyWatchAddKey(
    mut env: JNIEnv,
    _class: JClass,
    contract_key_hex: JString,
) {
    let Ok(key) = env.get_string(&contract_key_hex) else {
        return;
    };
    nearby_forward::watch_add_key(&key.to_string_lossy());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_freenet_androidnode_NativeBridge_nativeNearbyWatchPoll(
    mut env: JNIEnv,
    _class: JClass,
    timeout_ms: jint,
) -> jstring {
    jni_response(&mut env, |_| {
        let timeout = if timeout_ms < 0 { 0 } else { timeout_ms as u64 };
        nearby_forward::watch_poll(timeout)
    })
}

fn nearby_error(message: &str, detail: &str, export: bool) -> String {
    let text = if detail.is_empty() {
        message.to_owned()
    } else {
        format!("{message}: {detail}")
    };
    if export {
        serde_json::json!({
            "status": "error",
            "bytes": 0,
            "path": "",
            "message": text,
            "fetched": false,
        })
        .to_string()
    } else {
        serde_json::json!({
            "status": "error",
            "key": "",
            "message": text,
        })
        .to_string()
    }
}

fn freenet_build_info() -> String {
    let transport_generation = freenet::transport::version_mismatch_generation();
    format!(
        "Freenet core {FREENET_CORE_VERSION}; features: {}; default gateway port: {}; transport generation: {transport_generation}",
        freenet_features(),
        freenet::config::DEFAULT_GATEWAY_PORT,
    )
}

fn freenet_features() -> String {
    let mut features = Vec::new();
    if cfg!(feature = "freenet-redb") {
        features.push("redb");
    }
    if cfg!(feature = "freenet-trace") {
        features.push("trace");
    }
    if cfg!(feature = "freenet-wasmtime") {
        features.push("wasmtime-backend");
    }
    if cfg!(feature = "freenet-websocket") {
        features.push("websocket");
    }
    features.join(", ")
}

fn jni_string<F>(env: &mut JNIEnv, value: F) -> jstring
where
    F: FnOnce() -> String,
{
    match catch_unwind(AssertUnwindSafe(value)) {
        Ok(value) => match env.new_string(value) {
            Ok(output) => output.into_raw(),
            Err(_) => ptr::null_mut(),
        },
        Err(_) => match env.new_string("Rust JNI bridge panic was contained") {
            Ok(output) => output.into_raw(),
            Err(_) => ptr::null_mut(),
        },
    }
}

fn jni_response<F>(env: &mut JNIEnv, value: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> String,
{
    let response = catch_unwind(AssertUnwindSafe(|| value(env))).unwrap_or_else(|_| {
        jni_error_response(
            "NATIVE_PANIC",
            "A Rust panic reached the JNI boundary and was contained",
        )
    });
    match env.new_string(response) {
        Ok(output) => output.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

fn android_target_name() -> &'static str {
    #[cfg(target_arch = "aarch64")]
    {
        "aarch64-linux-android"
    }
    #[cfg(target_arch = "x86_64")]
    {
        "x86_64-linux-android"
    }
    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    {
        "unsupported-android-target"
    }
}

#[cfg(test)]
mod tests {
    use super::{BRIDGE_VERSION, FREENET_CORE_VERSION, android_target_name, freenet_build_info};

    #[test]
    fn build_metadata_is_not_empty() {
        assert_eq!(BRIDGE_VERSION, "0.1.0");
        assert!(!android_target_name().is_empty());
    }

    #[test]
    fn freenet_metadata_comes_from_the_linked_core_build() {
        let info = freenet_build_info();

        assert!(!FREENET_CORE_VERSION.is_empty());
        assert!(info.contains(&format!("Freenet core {FREENET_CORE_VERSION}")));
        assert!(info.contains("wasmtime-backend"));
        assert!(info.contains("default gateway port: 31337"));
        assert!(info.contains("transport generation: 0"));
    }
}

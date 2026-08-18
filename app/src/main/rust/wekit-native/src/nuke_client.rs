//! One-shot `/api/client/report` client built on the native-equivalent REST crypto.

use std::fmt;
use std::sync::OnceLock;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::nuke_crypto::rest::{
    EncryptedEnvelope, REST_STREAM, RestCryptoError, decrypt_json_bytes, encrypt_json_bytes,
    encrypt_json_bytes_with_iv,
};
use crate::nuke_crypto::sign::{
    DEFAULT_SIGNER_COMPANION_SECRET, DEFAULT_SIGNER_KID, DEFAULT_SIGNER_ROOT, NativeSignerContext,
    native_signer_mode, sign_native_canonical,
};

pub const CLIENT_REPORT_PATH: &str = "/api/client/report";
pub const CLIENT_USERS_PATH: &str = "/api/client/users";
pub const CLIENT_USER_ALREADY_EXISTS: &str = "CLIENT_USER_ALREADY_EXISTS";
pub const DEFAULT_CLIENT_REPORT_URL: &str = "https://www.guang233.com/api/client/report";
pub const DEFAULT_WECHAT_PLATFORM: &str = "WECHAT";
pub const DEFAULT_WECHAT_USER_ID: &str = "wxid_4f7k2m9p4r6t8v";
pub const DEFAULT_NUKE_BUILD_TIME_MILLIS: u64 = 1_783_695_933_338;
pub const DEFAULT_CLIENT_REPORT_MESSAGE: &str = "Nuke 1.0.0 initialized";

static PROCESS_START: OnceLock<Instant> = OnceLock::new();
/// Native `DAT_00fad090`: the first successful `CLOCK_MONOTONIC` millisecond
/// sample observed by the signer process. It is part of the N0I3 header.
static NATIVE_MONOTONIC_ORIGIN_MILLIS: OnceLock<u64> = OnceLock::new();

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ClientAuth {
    pub user_id: String,
    pub platform: String,
    pub timestamp: String,
    pub nonce: String,
    pub signature: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SignerObservations {
    pub runtime_flags: u64,
    pub timestamps: [u64; 3],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct NativeSyncSignerObservations {
    pub registration: SignerObservations,
    pub report: SignerObservations,
}

/// The report body serialized by the loader before REST v3 encryption.
///
/// Field order follows `J3.C0394h` / `J3.j` in `nuke_loader`. In particular,
/// nullable Android observations are retained as JSON nulls because the loader
/// configures Gson with `serializeNulls()`.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReportEnvironment {
    pub android_version: String,
    pub android_sdk_int: u32,
    pub device_brand: String,
    pub device_manufacturer: String,
    pub device_model: String,
    pub device_name: String,
    pub app_version: String,
    pub app_build: String,
    pub package_name: String,
    pub architecture: String,
    pub abi: String,
    pub xposed_framework: String,
    pub xposed_version: Option<String>,
    pub xposed_injection_mode: String,
    pub is_rooted: Option<bool>,
    pub is_emulator: Option<bool>,
    pub installer_package: Option<String>,
    pub locale: String,
    pub timezone: String,
    pub network_type: Option<String>,
    pub extra: Value,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct ClientReportRequest {
    pub message: String,
    pub environment: ReportEnvironment,
}

impl ClientReportRequest {
    pub fn to_json_bytes(&self) -> Result<Vec<u8>, ClientError> {
        serde_json::to_vec(self).map_err(|error| ClientError::Json(error.to_string()))
    }
}

/// Returns whether `value` is a WeChat user id in the format requested by the
/// native client flow: `wxid_` followed by fourteen lowercase alphanumeric
/// characters.
pub fn is_valid_wechat_user_id(value: &str) -> bool {
    let Some(suffix) = value.strip_prefix("wxid_") else {
        return false;
    };
    suffix.len() == 14
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
}

/// Reproduces the report message emitted by `D3.p.c` in the loader.
pub fn native_on_login_message(nickname: &str, alias: &str) -> String {
    format!(
        "Nuke onLogin: {nickname}/{alias}, module=1.0.0(release), buildTime={DEFAULT_NUKE_BUILD_TIME_MILLIS}"
    )
}

/// A deterministic Android/WeChat report. The SELinux block and message match
/// a successful `Experiments2` submission; other device fields remain fixed.
pub fn fixed_native_wechat_report() -> ClientReportRequest {
    let extra = json!({
        "hostPackage": "com.tencent.mm",
        "hostType": DEFAULT_WECHAT_PLATFORM,
        "dirtySepolicy": {
            "mode": "in_process",
            "sdk": 36,
            "release": "16",
            "available": true,
            "enabled": false,
            "enforced": false,
            "context": "u:r:untrusted_app:s0:c161,c257,c512,c768\0",
            "pidContext": "u:r:untrusted_app:s0:c161,c257,c512,c768\0",
            "procContext": "u:object_r:app_data_file:s0",
            "markers": {
                "magisk_context": false,
                "kernelsu_context": true,
                "lsposed_context": true,
                "xposed_context": true,
                "zygisk_next_rule": false,
            },
            "detected": {
                "xposed_context": true,
            },
        },
    });

    ClientReportRequest {
        message: DEFAULT_CLIENT_REPORT_MESSAGE.to_owned(),
        environment: ReportEnvironment {
            android_version: "16".to_owned(),
            android_sdk_int: 36,
            device_brand: "Xiaomi".to_owned(),
            device_manufacturer: "Xiaomi".to_owned(),
            device_model: "24031PN0DC".to_owned(),
            device_name: "houji".to_owned(),
            app_version: "1.0.0".to_owned(),
            app_build: "1".to_owned(),
            package_name: "com.tencent.mm".to_owned(),
            architecture: "aarch64".to_owned(),
            abi: "arm64-v8a".to_owned(),
            xposed_framework: "Xposed".to_owned(),
            xposed_version: None,
            xposed_injection_mode: "zygote".to_owned(),
            is_rooted: None,
            is_emulator: None,
            installer_package: None,
            locale: "zh-CN".to_owned(),
            timezone: "Asia/Shanghai".to_owned(),
            network_type: None,
            extra,
        },
    }
}

#[derive(Clone, Debug)]
pub struct OneShotResult {
    pub http_status: u16,
    pub request_body: String,
    pub canonical_payload: String,
    pub response_body: String,
    pub decrypted_json: Value,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PreparedReport {
    pub request_body: String,
    pub canonical_payload: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PreparedRegistration {
    pub request_body: String,
    pub canonical_payload: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegistrationResult {
    pub http_status: u16,
    pub request_body: String,
    pub canonical_payload: String,
    pub response_body: String,
    pub code: Option<String>,
    pub message: Option<String>,
}

/// Result of the synchronous native flow: registration completes before the
/// encrypted report is submitted.
#[derive(Clone, Debug)]
pub struct NativeSyncResult {
    pub registration: RegistrationResult,
    pub report: OneShotResult,
}

#[derive(Debug)]
pub enum ClientError {
    Crypto(RestCryptoError),
    Json(String),
    Time(String),
    Http(String),
    ApiRejected {
        http_status: u16,
        code: Option<String>,
        message: Option<String>,
        body: String,
    },
    MissingEncryptedData,
    InvalidResponseUtf8,
    InvalidResponseJson(String),
    InvalidEndpoint(String),
    IdentityMismatch,
}

impl fmt::Display for ClientError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Crypto(error) => write!(formatter, "REST crypto failed: {error}"),
            Self::Json(error) => write!(formatter, "JSON encoding failed: {error}"),
            Self::Time(error) => write!(
                formatter,
                "clock failed while building native signature: {error}"
            ),
            Self::Http(error) => write!(formatter, "HTTP request failed: {error}"),
            Self::ApiRejected {
                http_status,
                code,
                message,
                body,
            } => write!(
                formatter,
                "server rejected request (HTTP {http_status}, code={}, message={}): {body}",
                code.as_deref().unwrap_or("<none>"),
                message.as_deref().unwrap_or("<none>")
            ),
            Self::MissingEncryptedData => {
                formatter.write_str("successful response is missing encrypted data")
            }
            Self::InvalidResponseUtf8 => {
                formatter.write_str("decrypted response is not valid UTF-8")
            }
            Self::InvalidResponseJson(error) => {
                write!(formatter, "decrypted response is not valid JSON: {error}")
            }
            Self::InvalidEndpoint(error) => write!(formatter, "invalid client endpoint: {error}"),
            Self::IdentityMismatch => {
                formatter.write_str("registration and report identities must match")
            }
        }
    }
}

impl std::error::Error for ClientError {}

impl From<RestCryptoError> for ClientError {
    fn from(error: RestCryptoError) -> Self {
        Self::Crypto(error)
    }
}

#[derive(Debug, Deserialize)]
struct ApiResponse {
    #[serde(default)]
    success: bool,
    #[serde(default)]
    code: Option<String>,
    #[serde(default)]
    message: Option<String>,
    #[serde(default)]
    data: Option<EncryptedEnvelope>,
}

pub fn canonical_payload(auth: &ClientAuth, request_body: &str) -> String {
    canonical_payload_for_path(CLIENT_REPORT_PATH, auth, request_body)
}

/// Derives the registration endpoint paired with a `/api/client/report` URL.
pub fn client_users_endpoint_for_report(report_endpoint: &str) -> Result<String, ClientError> {
    let mut endpoint = reqwest::Url::parse(report_endpoint)
        .map_err(|error| ClientError::InvalidEndpoint(error.to_string()))?;
    if endpoint.path() != CLIENT_REPORT_PATH {
        return Err(ClientError::InvalidEndpoint(format!(
            "expected path {CLIENT_REPORT_PATH}, got {}",
            endpoint.path()
        )));
    }
    endpoint.set_path(CLIENT_USERS_PATH);
    endpoint.set_query(None);
    endpoint.set_fragment(None);
    Ok(endpoint.into())
}

pub fn canonical_payload_for_path(path: &str, auth: &ClientAuth, request_body: &str) -> String {
    [
        "POST",
        path,
        &auth.user_id,
        &auth.platform,
        &auth.timestamp,
        &auth.nonce,
        request_body,
    ]
    .join("\n")
}

/// Builds a native-equivalent signature with explicitly supplied runtime
/// observations. This permits deterministic vector tests while keeping host
/// clock collection outside the recovered packet algorithm.
pub fn native_sign_client_payload_with_signer_observations(
    canonical: &str,
    observations: SignerObservations,
) -> String {
    sign_native_canonical(NativeSignerContext {
        root: DEFAULT_SIGNER_ROOT,
        companion_secret: DEFAULT_SIGNER_COMPANION_SECRET,
        canonical: canonical.as_bytes(),
        runtime_flags: observations.runtime_flags,
        mode: native_signer_mode(observations.runtime_flags),
        kid: DEFAULT_SIGNER_KID,
        timestamps: observations.timestamps,
        accepted_samples: 0,
    })
}

/// Backwards-compatible helper for tests that already name the raw native
/// observations separately.
pub fn native_sign_client_payload_with_observations(
    canonical: &str,
    runtime_flags: u64,
    timestamps: [u64; 3],
) -> String {
    native_sign_client_payload_with_signer_observations(
        canonical,
        SignerObservations {
            runtime_flags,
            timestamps,
        },
    )
}

/// Samples the host clocks in the same three-value shape used by native
/// FUN_00e9dfd0, while leaving the runtime flag source explicit.
pub fn current_signer_observations(runtime_flags: u64) -> Result<SignerObservations, ClientError> {
    let unix_seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| ClientError::Time(error.to_string()))?
        .as_secs();
    let monotonic_millis = native_monotonic_millis();
    let monotonic_origin = *NATIVE_MONOTONIC_ORIGIN_MILLIS.get_or_init(|| monotonic_millis);
    Ok(SignerObservations {
        runtime_flags,
        timestamps: native_signer_timestamps(unix_seconds, monotonic_millis, monotonic_origin),
    })
}

/// Builds a host-side equivalent of native `FUN_00e9dfd0` for one canonical
/// client request. The native process contributes runtime-integrity bits; the
/// standalone host implementation uses zero runtime-integrity bits and its own
/// wall clock plus native-equivalent `CLOCK_MONOTONIC` observations. Native
/// additionally binds elapsed monotonic milliseconds since the first signing
/// call in the third N0I3 timestamp slot.
pub fn native_sign_client_payload(canonical: &str) -> Result<String, ClientError> {
    Ok(native_sign_client_payload_with_signer_observations(
        canonical,
        current_signer_observations(0)?,
    ))
}

pub fn current_native_sync_signer_observations(
    runtime_flags: u64,
) -> Result<NativeSyncSignerObservations, ClientError> {
    Ok(NativeSyncSignerObservations {
        registration: current_signer_observations(runtime_flags)?,
        report: current_signer_observations(runtime_flags)?,
    })
}

/// Converts the three time values that `FUN_00e9dfd0` stores in its N0I3
/// descriptor: epoch seconds, boot-relative milliseconds, and elapsed
/// boot-relative milliseconds since the first signer call.
fn native_signer_timestamps(
    unix_seconds: u64,
    monotonic_millis: u64,
    monotonic_origin_millis: u64,
) -> [u64; 3] {
    [
        unix_seconds,
        monotonic_millis,
        monotonic_millis.saturating_sub(monotonic_origin_millis),
    ]
}

fn native_monotonic_millis() -> u64 {
    #[cfg(unix)]
    {
        let mut timestamp = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut timestamp) } == 0 {
            if let (Ok(seconds), Ok(nanoseconds)) = (
                u64::try_from(timestamp.tv_sec),
                u64::try_from(timestamp.tv_nsec),
            ) {
                return seconds
                    .saturating_mul(1_000)
                    .saturating_add(nanoseconds / 1_000_000);
            }
        }
    }

    PROCESS_START
        .get_or_init(Instant::now)
        .elapsed()
        .as_millis() as u64
}

pub fn send_one_report(
    endpoint: &str,
    report_json: &[u8],
    auth: &ClientAuth,
) -> Result<OneShotResult, ClientError> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    send_one_report_with_client(&client, endpoint, report_json, auth)
}

pub fn prepare_one_report(
    report_json: &[u8],
    auth: &ClientAuth,
) -> Result<PreparedReport, ClientError> {
    serde_json::from_slice::<Value>(report_json)
        .map_err(|error| ClientError::Json(format!("report is not valid JSON: {error}")))?;
    let envelope = encrypt_json_bytes(report_json, REST_STREAM)?;
    prepare_envelope(envelope, auth)
}

pub fn prepare_one_report_with_iv(
    report_json: &[u8],
    auth: &ClientAuth,
    iv: &[u8; 24],
) -> Result<PreparedReport, ClientError> {
    serde_json::from_slice::<Value>(report_json)
        .map_err(|error| ClientError::Json(format!("report is not valid JSON: {error}")))?;
    prepare_envelope(
        encrypt_json_bytes_with_iv(report_json, REST_STREAM, iv),
        auth,
    )
}

/// Builds the unencrypted JSON registration body emitted by `FUN_00ec22b8`.
/// The native dispatcher signs this request with the same seven-line canonical
/// form as reports, changing only the request path and body.
pub fn prepare_registration(auth: &ClientAuth) -> Result<PreparedRegistration, ClientError> {
    let user_id = serde_json::to_string(&auth.user_id)
        .map_err(|error| ClientError::Json(error.to_string()))?;
    let platform = serde_json::to_string(&auth.platform)
        .map_err(|error| ClientError::Json(error.to_string()))?;
    let request_body = format!("{{\"userId\":{user_id},\"platform\":{platform}}}");
    let canonical_payload = canonical_payload_for_path(CLIENT_USERS_PATH, auth, &request_body);
    Ok(PreparedRegistration {
        request_body,
        canonical_payload,
    })
}

fn prepare_envelope(
    envelope: EncryptedEnvelope,
    auth: &ClientAuth,
) -> Result<PreparedReport, ClientError> {
    let request_body =
        serde_json::to_string(&envelope).map_err(|error| ClientError::Json(error.to_string()))?;
    let canonical_payload = canonical_payload(auth, &request_body);
    Ok(PreparedReport {
        request_body,
        canonical_payload,
    })
}

pub fn send_one_report_with_client(
    client: &Client,
    endpoint: &str,
    report_json: &[u8],
    auth: &ClientAuth,
) -> Result<OneShotResult, ClientError> {
    let prepared = prepare_one_report(report_json, auth)?;
    send_prepared_report_with_client(client, endpoint, &prepared, auth)
}

/// Reproduces the native synchronous flow in `FUN_00ec22b8`: register the
/// identity first, then submit the encrypted report. Registration accepts only
/// HTTP 201 or HTTP 409 with the exact plain native idempotency response.
pub fn send_native_sync_report_auto_signed_with_client(
    client: &Client,
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
) -> Result<NativeSyncResult, ClientError> {
    send_native_sync_report_auto_signed_with_runtime_flags_with_client(
        client,
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        0,
    )
}

pub fn send_native_sync_report_auto_signed_with_runtime_flags_with_client(
    client: &Client,
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    runtime_flags: u64,
) -> Result<NativeSyncResult, ClientError> {
    send_native_sync_report_auto_signed_with_observations_with_client(
        client,
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        current_native_sync_signer_observations(runtime_flags)?,
    )
}

pub fn send_native_sync_report_auto_signed_with_observations_with_client(
    client: &Client,
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    observations: NativeSyncSignerObservations,
) -> Result<NativeSyncResult, ClientError> {
    if registration_auth.user_id != report_auth.user_id
        || registration_auth.platform != report_auth.platform
    {
        return Err(ClientError::IdentityMismatch);
    }

    let prepared_registration = prepare_registration(registration_auth)?;
    let signed_registration = sign_prepared_registration_with_signer_observations(
        &prepared_registration,
        registration_auth,
        observations.registration,
    )?;
    let registration = send_prepared_registration_with_client(
        client,
        registration_endpoint,
        &prepared_registration,
        &signed_registration,
    )?;

    let prepared_report = prepare_one_report(report_json, report_auth)?;
    let signed_report = sign_prepared_report_with_signer_observations(
        &prepared_report,
        report_auth,
        observations.report,
    )?;
    let report = send_prepared_report_with_client(
        client,
        report_endpoint,
        &prepared_report,
        &signed_report,
    )?;

    Ok(NativeSyncResult {
        registration,
        report,
    })
}

/// Deterministic variant of the native flow for local packet-level tests and
/// externally paired request-signature experiments.
pub fn send_native_sync_report_auto_signed_with_iv_with_client(
    client: &Client,
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    iv: &[u8; 24],
) -> Result<NativeSyncResult, ClientError> {
    send_native_sync_report_auto_signed_with_iv_with_runtime_flags_with_client(
        client,
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        iv,
        0,
    )
}

pub fn send_native_sync_report_auto_signed_with_iv_with_runtime_flags_with_client(
    client: &Client,
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    iv: &[u8; 24],
    runtime_flags: u64,
) -> Result<NativeSyncResult, ClientError> {
    send_native_sync_report_auto_signed_with_iv_with_observations_with_client(
        client,
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        iv,
        current_native_sync_signer_observations(runtime_flags)?,
    )
}

pub fn send_native_sync_report_auto_signed_with_iv_with_observations_with_client(
    client: &Client,
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    iv: &[u8; 24],
    observations: NativeSyncSignerObservations,
) -> Result<NativeSyncResult, ClientError> {
    if registration_auth.user_id != report_auth.user_id
        || registration_auth.platform != report_auth.platform
    {
        return Err(ClientError::IdentityMismatch);
    }

    let prepared_registration = prepare_registration(registration_auth)?;
    let signed_registration = sign_prepared_registration_with_signer_observations(
        &prepared_registration,
        registration_auth,
        observations.registration,
    )?;
    let registration = send_prepared_registration_with_client(
        client,
        registration_endpoint,
        &prepared_registration,
        &signed_registration,
    )?;

    let prepared_report = prepare_one_report_with_iv(report_json, report_auth, iv)?;
    let signed_report = sign_prepared_report_with_signer_observations(
        &prepared_report,
        report_auth,
        observations.report,
    )?;
    let report = send_prepared_report_with_client(
        client,
        report_endpoint,
        &prepared_report,
        &signed_report,
    )?;

    Ok(NativeSyncResult {
        registration,
        report,
    })
}

/// Builds a direct, no-proxy client and executes the native registration then
/// report sequence.
pub fn send_native_sync_report_auto_signed(
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
) -> Result<NativeSyncResult, ClientError> {
    send_native_sync_report_auto_signed_with_runtime_flags(
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        0,
    )
}

pub fn send_native_sync_report_auto_signed_with_runtime_flags(
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    runtime_flags: u64,
) -> Result<NativeSyncResult, ClientError> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    send_native_sync_report_auto_signed_with_runtime_flags_with_client(
        &client,
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        runtime_flags,
    )
}

/// Direct-client deterministic variant of the native registration then report
/// sequence. Intended for controlled interoperability diagnostics.
pub fn send_native_sync_report_auto_signed_with_iv(
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    iv: &[u8; 24],
) -> Result<NativeSyncResult, ClientError> {
    send_native_sync_report_auto_signed_with_iv_with_runtime_flags(
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        iv,
        0,
    )
}

pub fn send_native_sync_report_auto_signed_with_iv_with_runtime_flags(
    registration_endpoint: &str,
    report_endpoint: &str,
    report_json: &[u8],
    registration_auth: &ClientAuth,
    report_auth: &ClientAuth,
    iv: &[u8; 24],
    runtime_flags: u64,
) -> Result<NativeSyncResult, ClientError> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    send_native_sync_report_auto_signed_with_iv_with_runtime_flags_with_client(
        &client,
        registration_endpoint,
        report_endpoint,
        report_json,
        registration_auth,
        report_auth,
        iv,
        runtime_flags,
    )
}

/// Prepares exactly one encrypted report, signs its canonical form in Rust, and
/// sends that one request through the supplied client.
pub fn send_one_report_auto_signed_with_client(
    client: &Client,
    endpoint: &str,
    report_json: &[u8],
    auth: &ClientAuth,
) -> Result<OneShotResult, ClientError> {
    let prepared = prepare_one_report(report_json, auth)?;
    let signed_auth = sign_prepared_report(&prepared, auth)?;
    send_prepared_report_with_client(client, endpoint, &prepared, &signed_auth)
}

/// Generates the native signature for an already encrypted/prepared request.
pub fn sign_prepared_report(
    prepared: &PreparedReport,
    auth: &ClientAuth,
) -> Result<ClientAuth, ClientError> {
    sign_prepared_report_with_signer_observations(prepared, auth, current_signer_observations(0)?)
}

pub fn sign_prepared_report_with_runtime_flags(
    prepared: &PreparedReport,
    auth: &ClientAuth,
    runtime_flags: u64,
) -> Result<ClientAuth, ClientError> {
    sign_prepared_report_with_signer_observations(
        prepared,
        auth,
        current_signer_observations(runtime_flags)?,
    )
}

pub fn sign_prepared_report_with_signer_observations(
    prepared: &PreparedReport,
    auth: &ClientAuth,
    observations: SignerObservations,
) -> Result<ClientAuth, ClientError> {
    let mut signed = auth.clone();
    signed.signature = native_sign_client_payload_with_signer_observations(
        &prepared.canonical_payload,
        observations,
    );
    Ok(signed)
}

/// Generates the native signature for an already prepared registration.
pub fn sign_prepared_registration(
    prepared: &PreparedRegistration,
    auth: &ClientAuth,
) -> Result<ClientAuth, ClientError> {
    sign_prepared_registration_with_signer_observations(
        prepared,
        auth,
        current_signer_observations(0)?,
    )
}

pub fn sign_prepared_registration_with_runtime_flags(
    prepared: &PreparedRegistration,
    auth: &ClientAuth,
    runtime_flags: u64,
) -> Result<ClientAuth, ClientError> {
    sign_prepared_registration_with_signer_observations(
        prepared,
        auth,
        current_signer_observations(runtime_flags)?,
    )
}

pub fn sign_prepared_registration_with_signer_observations(
    prepared: &PreparedRegistration,
    auth: &ClientAuth,
    observations: SignerObservations,
) -> Result<ClientAuth, ClientError> {
    let mut signed = auth.clone();
    signed.signature = native_sign_client_payload_with_signer_observations(
        &prepared.canonical_payload,
        observations,
    );
    Ok(signed)
}

pub fn send_prepared_report(
    endpoint: &str,
    prepared: &PreparedReport,
    auth: &ClientAuth,
) -> Result<OneShotResult, ClientError> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    send_prepared_report_with_client(&client, endpoint, prepared, auth)
}

pub fn send_prepared_registration(
    endpoint: &str,
    prepared: &PreparedRegistration,
    auth: &ClientAuth,
) -> Result<RegistrationResult, ClientError> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    send_prepared_registration_with_client(&client, endpoint, prepared, auth)
}

pub fn send_prepared_report_with_client(
    client: &Client,
    endpoint: &str,
    prepared: &PreparedReport,
    auth: &ClientAuth,
) -> Result<OneShotResult, ClientError> {
    if auth.signature.is_empty() {
        return Err(ClientError::Json(
            "request has no signature; call sign_prepared_report first".to_owned(),
        ));
    }

    let (http_status, response_body) =
        post_signed_request(client, endpoint, &prepared.request_body, auth)?;
    let api: ApiResponse = serde_json::from_str(&response_body)
        .map_err(|error| ClientError::Json(format!("invalid API response: {error}")))?;

    if !(200..300).contains(&http_status) || !api.success {
        return Err(ClientError::ApiRejected {
            http_status,
            code: api.code,
            message: api.message,
            body: response_body,
        });
    }

    let encrypted_data = api.data.ok_or(ClientError::MissingEncryptedData)?;
    let plaintext = decrypt_json_bytes(&encrypted_data, REST_STREAM)?;
    let plaintext_utf8 =
        std::str::from_utf8(&plaintext).map_err(|_| ClientError::InvalidResponseUtf8)?;
    let decrypted_json = serde_json::from_str(plaintext_utf8)
        .map_err(|error| ClientError::InvalidResponseJson(error.to_string()))?;

    Ok(OneShotResult {
        http_status,
        request_body: prepared.request_body.clone(),
        canonical_payload: prepared.canonical_payload.clone(),
        response_body,
        decrypted_json,
    })
}

pub fn send_prepared_registration_with_client(
    client: &Client,
    endpoint: &str,
    prepared: &PreparedRegistration,
    auth: &ClientAuth,
) -> Result<RegistrationResult, ClientError> {
    if auth.signature.is_empty() {
        return Err(ClientError::Json(
            "request has no signature; call sign_prepared_registration first".to_owned(),
        ));
    }

    let (http_status, response_body) =
        post_signed_request(client, endpoint, &prepared.request_body, auth)?;
    let api = serde_json::from_str::<ApiResponse>(&response_body).ok();
    let accepted =
        http_status == 201 || (http_status == 409 && response_body == CLIENT_USER_ALREADY_EXISTS);
    if !accepted {
        return Err(ClientError::ApiRejected {
            http_status,
            code: api.as_ref().and_then(|response| response.code.clone()),
            message: api.as_ref().and_then(|response| response.message.clone()),
            body: response_body,
        });
    }

    Ok(RegistrationResult {
        http_status,
        request_body: prepared.request_body.clone(),
        canonical_payload: prepared.canonical_payload.clone(),
        response_body,
        code: api.as_ref().and_then(|response| response.code.clone()),
        message: api.and_then(|response| response.message),
    })
}

fn post_signed_request(
    client: &Client,
    endpoint: &str,
    request_body: &str,
    auth: &ClientAuth,
) -> Result<(u16, String), ClientError> {
    let response = client
        .post(endpoint)
        .header("Content-Type", "application/json")
        .header("X-Client-Id", &auth.user_id)
        .header("X-Platform", &auth.platform)
        .header("X-Timestamp", &auth.timestamp)
        .header("X-Nonce", &auth.nonce)
        .header("X-Signature", &auth.signature)
        .body(request_body.to_owned())
        .send()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    let http_status = response.status().as_u16();
    let response_body = response
        .text()
        .map_err(|error| ClientError::Http(error.to_string()))?;
    Ok((http_status, response_body))
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    use reqwest::blocking::Client;
    use serde_json::{Value, json};

    use super::{
        CLIENT_REPORT_PATH, CLIENT_USER_ALREADY_EXISTS, CLIENT_USERS_PATH, ClientAuth,
        DEFAULT_CLIENT_REPORT_MESSAGE, DEFAULT_WECHAT_PLATFORM, DEFAULT_WECHAT_USER_ID,
        NativeSyncSignerObservations, SignerObservations, canonical_payload,
        canonical_payload_for_path, client_users_endpoint_for_report, fixed_native_wechat_report,
        is_valid_wechat_user_id, native_sign_client_payload_with_observations,
        native_sign_client_payload_with_signer_observations, native_signer_timestamps,
        prepare_one_report_with_iv, prepare_registration,
        send_native_sync_report_auto_signed_with_iv_with_client,
        send_native_sync_report_auto_signed_with_iv_with_observations_with_client,
        send_prepared_registration_with_client, send_prepared_report_with_client,
        sign_prepared_registration, sign_prepared_report,
    };
    use crate::nuke_crypto::rest::{
        EncryptedEnvelope, REST_STREAM, decrypt_json_bytes, encrypt_json_bytes_with_iv,
    };

    fn read_request(stream: &mut std::net::TcpStream) -> Vec<u8> {
        let mut request = Vec::new();
        let mut buffer = [0u8; 4096];
        let mut expected_len = None;
        loop {
            let count = stream.read(&mut buffer).unwrap();
            assert!(count > 0, "client closed before request completed");
            request.extend_from_slice(&buffer[..count]);
            if expected_len.is_none() {
                if let Some(header_end) =
                    request.windows(4).position(|window| window == b"\r\n\r\n")
                {
                    let headers = String::from_utf8_lossy(&request[..header_end]);
                    let content_length = headers
                        .lines()
                        .find_map(|line| {
                            let (name, value) = line.split_once(':')?;
                            name.eq_ignore_ascii_case("content-length")
                                .then(|| value.trim().parse::<usize>().unwrap())
                        })
                        .unwrap();
                    expected_len = Some(header_end + 4 + content_length);
                }
            }
            if expected_len.is_some_and(|length| request.len() >= length) {
                return request;
            }
        }
    }

    #[test]
    fn fixed_report_matches_successful_experiments_message_and_sepolicy() {
        assert!(is_valid_wechat_user_id(DEFAULT_WECHAT_USER_ID));
        assert!(!is_valid_wechat_user_id("wxid_short"));
        assert!(!is_valid_wechat_user_id("wxid_3F7k2m9p4r6t8v"));

        let report = fixed_native_wechat_report();
        let value: Value = serde_json::from_slice(&report.to_json_bytes().unwrap()).unwrap();
        assert_eq!(value["message"], DEFAULT_CLIENT_REPORT_MESSAGE);

        let environment = &value["environment"];
        assert_eq!(environment["androidVersion"], "15");
        assert_eq!(environment["androidSdkInt"], 35);
        assert_eq!(environment["deviceBrand"], "Xiaomi");
        assert_eq!(environment["deviceManufacturer"], "Xiaomi");
        assert_eq!(environment["deviceModel"], "24031PN0DC");
        assert_eq!(environment["deviceName"], "houji");
        assert_eq!(environment["appVersion"], "1.0.0");
        assert_eq!(environment["appBuild"], "1");
        assert_eq!(environment["packageName"], "com.tencent.mm");
        assert_eq!(environment["architecture"], "aarch64");
        assert_eq!(environment["abi"], "arm64-v8a");
        assert_eq!(environment["xposedFramework"], "Xposed");
        assert!(environment["xposedVersion"].is_null());
        assert_eq!(environment["xposedInjectionMode"], "zygote");
        assert!(environment["isRooted"].is_null());
        assert!(environment["isEmulator"].is_null());
        assert!(environment["installerPackage"].is_null());
        assert_eq!(environment["locale"], "zh-CN");
        assert_eq!(environment["timezone"], "Asia/Shanghai");
        assert!(environment["networkType"].is_null());

        let dirty_sepolicy = &environment["extra"]["dirtySepolicy"];
        assert_eq!(environment["extra"]["hostPackage"], "com.tencent.mm");
        assert_eq!(environment["extra"]["hostType"], DEFAULT_WECHAT_PLATFORM);
        assert_eq!(dirty_sepolicy["mode"], "in_process");
        assert_eq!(dirty_sepolicy["sdk"], 36);
        assert_eq!(dirty_sepolicy["release"], "16");
        assert_eq!(dirty_sepolicy["available"], true);
        assert_eq!(dirty_sepolicy["enabled"], false);
        assert_eq!(dirty_sepolicy["enforced"], false);
        assert_eq!(
            dirty_sepolicy["context"],
            "u:r:untrusted_app:s0:c161,c257,c512,c768\0"
        );
        assert_eq!(
            dirty_sepolicy["pidContext"],
            "u:r:untrusted_app:s0:c161,c257,c512,c768\0"
        );
        assert_eq!(dirty_sepolicy["procContext"], "u:object_r:app_data_file:s0");
        assert!(dirty_sepolicy["markers"].is_object());
        assert_eq!(dirty_sepolicy["markers"]["magisk_context"], false);
        assert_eq!(dirty_sepolicy["markers"]["kernelsu_context"], true);
        assert_eq!(dirty_sepolicy["markers"]["lsposed_context"], true);
        assert_eq!(dirty_sepolicy["markers"]["xposed_context"], true);
        assert_eq!(dirty_sepolicy["markers"]["zygisk_next_rule"], false);
        assert!(dirty_sepolicy["detected"].is_object());
        assert_eq!(dirty_sepolicy["detected"]["xposed_context"], true);
    }

    #[test]
    fn single_request_round_trip_decrypts_native_like_report_and_response() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!(
            "http://{}/api/client/report",
            listener.local_addr().unwrap()
        );
        let response_envelope = encrypt_json_bytes_with_iv(
            br#"{"accepted":true,"requestId":"one-shot"}"#,
            REST_STREAM,
            &[0x44; 24],
        );
        let response_body = serde_json::to_string(&json!({
            "success": true,
            "data": response_envelope,
        }))
        .unwrap();

        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let request = read_request(&mut stream);
            let header_end = request
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            let headers = String::from_utf8_lossy(&request[..header_end]).to_ascii_lowercase();
            assert!(headers.starts_with("post /api/client/report http/1.1"));
            assert!(headers.contains(&format!("x-client-id: {DEFAULT_WECHAT_USER_ID}")));
            assert!(headers.contains("x-platform: wechat"));
            assert!(headers.contains("x-timestamp: 1784559820"));
            assert!(headers.contains("x-nonce: one-shot-nonce"));
            let signature = headers
                .lines()
                .find_map(|line| line.strip_prefix("x-signature: "))
                .unwrap();
            assert!(signature.len() >= 1344);
            assert_eq!(signature.len() % 2, 0);
            assert!(
                signature
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            );

            let envelope: EncryptedEnvelope =
                serde_json::from_slice(&request[header_end + 4..]).unwrap();
            assert_eq!(envelope.v, 3);
            assert_eq!(envelope.kid, "d8e39774");
            let submitted_report: Value =
                serde_json::from_slice(&decrypt_json_bytes(&envelope, REST_STREAM).unwrap())
                    .unwrap();
            assert_eq!(
                submitted_report,
                serde_json::to_value(fixed_native_wechat_report()).unwrap()
            );

            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response_body.len(),
                response_body
            )
            .unwrap();
        });

        let auth = ClientAuth {
            user_id: DEFAULT_WECHAT_USER_ID.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp: "1784559820".to_owned(),
            nonce: "one-shot-nonce".to_owned(),
            signature: String::new(),
        };
        let client = Client::builder().no_proxy().build().unwrap();
        let report_json = fixed_native_wechat_report().to_json_bytes().unwrap();
        let prepared = prepare_one_report_with_iv(&report_json, &auth, &[0x33; 24]).unwrap();
        let signed_auth = sign_prepared_report(&prepared, &auth).unwrap();
        let result =
            send_prepared_report_with_client(&client, &endpoint, &prepared, &signed_auth).unwrap();
        server.join().unwrap();

        assert_eq!(result.http_status, 200);
        assert_eq!(result.decrypted_json["accepted"], true);
        assert_eq!(result.decrypted_json["requestId"], "one-shot");
        assert_eq!(
            result.canonical_payload,
            canonical_payload(&auth, &result.request_body)
        );
    }

    #[test]
    fn registration_round_trip_uses_native_json_and_auth_headers() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!("http://{}/api/client/users", listener.local_addr().unwrap());
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let request = read_request(&mut stream);
            let header_end = request
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            let headers = String::from_utf8_lossy(&request[..header_end]).to_ascii_lowercase();
            assert!(headers.starts_with("post /api/client/users http/1.1"));
            assert!(headers.contains(&format!("x-client-id: {DEFAULT_WECHAT_USER_ID}")));
            assert!(headers.contains("x-platform: wechat"));
            assert!(headers.contains("x-timestamp: 1784559820"));
            assert!(headers.contains("x-nonce: registration-nonce"));
            let signature = headers
                .lines()
                .find_map(|line| line.strip_prefix("x-signature: "))
                .unwrap();
            assert!(signature.len() >= 1344);
            assert_eq!(signature.len() % 2, 0);
            assert!(
                signature
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            );
            let expected_body = format!(
                r#"{{"userId":"{DEFAULT_WECHAT_USER_ID}","platform":"{DEFAULT_WECHAT_PLATFORM}"}}"#
            );
            assert_eq!(&request[header_end + 4..], expected_body.as_bytes());

            let response_body = r#"{"success":true,"code":"REGISTERED"}"#;
            write!(
                stream,
                "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response_body.len(),
                response_body
            )
            .unwrap();
        });

        let auth = ClientAuth {
            user_id: DEFAULT_WECHAT_USER_ID.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp: "1784559820".to_owned(),
            nonce: "registration-nonce".to_owned(),
            signature: String::new(),
        };
        let prepared = prepare_registration(&auth).unwrap();
        assert_eq!(
            prepared.canonical_payload,
            canonical_payload_for_path(CLIENT_USERS_PATH, &auth, &prepared.request_body)
        );
        let signed_auth = sign_prepared_registration(&prepared, &auth).unwrap();
        let client = Client::builder().no_proxy().build().unwrap();
        let result =
            send_prepared_registration_with_client(&client, &endpoint, &prepared, &signed_auth)
                .unwrap();
        server.join().unwrap();

        assert_eq!(result.http_status, 201);
        assert_eq!(result.code.as_deref(), Some("REGISTERED"));
        assert_eq!(
            result.canonical_payload,
            canonical_payload_for_path(CLIENT_USERS_PATH, &auth, &result.request_body)
        );
    }

    #[test]
    fn native_sync_registers_before_report_after_created_response() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let registration_endpoint = format!("{base}{CLIENT_USERS_PATH}");
        let report_endpoint = format!("{base}{CLIENT_REPORT_PATH}");
        let response_envelope = encrypt_json_bytes_with_iv(
            br#"{"accepted":true,"requestId":"native-sync-created"}"#,
            REST_STREAM,
            &[0x55; 24],
        );
        let report_response = serde_json::to_string(&json!({
            "success": true,
            "data": response_envelope,
        }))
        .unwrap();

        let server = thread::spawn(move || {
            let (mut registration_stream, _) = listener.accept().unwrap();
            let registration = read_request(&mut registration_stream);
            let registration_header_end = registration
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            let registration_headers =
                String::from_utf8_lossy(&registration[..registration_header_end])
                    .to_ascii_lowercase();
            assert!(registration_headers.starts_with("post /api/client/users http/1.1"));
            assert!(registration_headers.contains("x-nonce: registration-sync-nonce"));
            assert!(registration_headers.contains("x-signature: "));
            let expected_registration = format!(
                r#"{{"userId":"{DEFAULT_WECHAT_USER_ID}","platform":"{DEFAULT_WECHAT_PLATFORM}"}}"#
            );
            assert_eq!(
                &registration[registration_header_end + 4..],
                expected_registration.as_bytes()
            );
            write!(
                registration_stream,
                "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nContent-Length: 7\r\nConnection: close\r\n\r\ncreated"
            )
            .unwrap();

            let (mut report_stream, _) = listener.accept().unwrap();
            let report = read_request(&mut report_stream);
            let report_header_end = report
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            let report_headers =
                String::from_utf8_lossy(&report[..report_header_end]).to_ascii_lowercase();
            assert!(report_headers.starts_with("post /api/client/report http/1.1"));
            assert!(report_headers.contains("x-nonce: report-sync-nonce"));
            let envelope: EncryptedEnvelope =
                serde_json::from_slice(&report[report_header_end + 4..]).unwrap();
            assert_eq!(
                serde_json::from_slice::<Value>(
                    &decrypt_json_bytes(&envelope, REST_STREAM).unwrap()
                )
                .unwrap(),
                serde_json::to_value(fixed_native_wechat_report()).unwrap()
            );
            write!(
                report_stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                report_response.len(),
                report_response
            )
            .unwrap();
        });

        let registration_auth = ClientAuth {
            user_id: DEFAULT_WECHAT_USER_ID.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp: "1784559820".to_owned(),
            nonce: "registration-sync-nonce".to_owned(),
            signature: String::new(),
        };
        let report_auth = ClientAuth {
            nonce: "report-sync-nonce".to_owned(),
            ..registration_auth.clone()
        };
        let report_json = fixed_native_wechat_report().to_json_bytes().unwrap();
        let client = Client::builder().no_proxy().build().unwrap();
        let result = send_native_sync_report_auto_signed_with_iv_with_client(
            &client,
            &registration_endpoint,
            &report_endpoint,
            &report_json,
            &registration_auth,
            &report_auth,
            &[0x66; 24],
        )
        .unwrap();
        server.join().unwrap();

        assert_eq!(result.registration.http_status, 201);
        assert_eq!(result.registration.response_body, "created");
        assert_eq!(
            result.report.decrypted_json["requestId"],
            "native-sync-created"
        );
    }

    #[test]
    fn native_sync_uses_explicit_signer_observations_for_both_requests() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let registration_endpoint = format!("{base}{CLIENT_USERS_PATH}");
        let report_endpoint = format!("{base}{CLIENT_REPORT_PATH}");
        let response_envelope = encrypt_json_bytes_with_iv(
            br#"{"accepted":true,"requestId":"native-sync-observed"}"#,
            REST_STREAM,
            &[0x99; 24],
        );
        let report_response = serde_json::to_string(&json!({
            "success": true,
            "data": response_envelope,
        }))
        .unwrap();

        let registration_auth = ClientAuth {
            user_id: DEFAULT_WECHAT_USER_ID.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp: "1784559820".to_owned(),
            nonce: "registration-observed-nonce".to_owned(),
            signature: String::new(),
        };
        let report_auth = ClientAuth {
            nonce: "report-observed-nonce".to_owned(),
            ..registration_auth.clone()
        };
        let observations = NativeSyncSignerObservations {
            registration: SignerObservations {
                runtime_flags: 0x2080_6ee0_8021_5080,
                timestamps: [1_784_888_302, 3_126, 0],
            },
            report: SignerObservations {
                runtime_flags: 0x2080_6ee0_8421_5084,
                timestamps: [1_784_890_710, 9_549, 0],
            },
        };
        let prepared_registration = prepare_registration(&registration_auth).unwrap();
        let expected_registration_signature = native_sign_client_payload_with_signer_observations(
            &prepared_registration.canonical_payload,
            observations.registration,
        );
        let report_json = fixed_native_wechat_report().to_json_bytes().unwrap();
        let prepared_report =
            prepare_one_report_with_iv(&report_json, &report_auth, &[0x44; 24]).unwrap();
        let expected_report_signature = native_sign_client_payload_with_signer_observations(
            &prepared_report.canonical_payload,
            observations.report,
        );
        assert_ne!(expected_registration_signature, expected_report_signature);

        let server = thread::spawn(move || {
            let (mut registration_stream, _) = listener.accept().unwrap();
            let registration = read_request(&mut registration_stream);
            let registration_header_end = registration
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            let registration_headers =
                String::from_utf8_lossy(&registration[..registration_header_end])
                    .to_ascii_lowercase();
            assert!(registration_headers.starts_with("post /api/client/users http/1.1"));
            assert!(
                registration_headers
                    .contains(&format!("x-signature: {expected_registration_signature}"))
            );
            assert_eq!(
                &registration[registration_header_end + 4..],
                prepared_registration.request_body.as_bytes()
            );
            write!(
                registration_stream,
                "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nContent-Length: 7\r\nConnection: close\r\n\r\ncreated"
            )
            .unwrap();

            let (mut report_stream, _) = listener.accept().unwrap();
            let report = read_request(&mut report_stream);
            let report_header_end = report
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            let report_headers =
                String::from_utf8_lossy(&report[..report_header_end]).to_ascii_lowercase();
            assert!(report_headers.starts_with("post /api/client/report http/1.1"));
            assert!(report_headers.contains(&format!("x-signature: {expected_report_signature}")));
            let envelope: EncryptedEnvelope =
                serde_json::from_slice(&report[report_header_end + 4..]).unwrap();
            assert_eq!(
                serde_json::from_slice::<Value>(
                    &decrypt_json_bytes(&envelope, REST_STREAM).unwrap()
                )
                .unwrap(),
                serde_json::to_value(fixed_native_wechat_report()).unwrap()
            );
            write!(
                report_stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                report_response.len(),
                report_response
            )
            .unwrap();
        });

        let client = Client::builder().no_proxy().build().unwrap();
        let result = send_native_sync_report_auto_signed_with_iv_with_observations_with_client(
            &client,
            &registration_endpoint,
            &report_endpoint,
            &report_json,
            &registration_auth,
            &report_auth,
            &[0x44; 24],
            observations,
        )
        .unwrap();
        server.join().unwrap();

        assert_eq!(result.registration.http_status, 201);
        assert_eq!(
            result.report.decrypted_json["requestId"],
            "native-sync-observed"
        );
    }

    #[test]
    fn native_sync_accepts_exact_already_exists_rebind_response() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let registration_endpoint = format!("{base}{CLIENT_USERS_PATH}");
        let report_endpoint = format!("{base}{CLIENT_REPORT_PATH}");
        let response_envelope = encrypt_json_bytes_with_iv(
            br#"{"accepted":true,"requestId":"native-sync-rebind"}"#,
            REST_STREAM,
            &[0x77; 24],
        );
        let report_response = serde_json::to_string(&json!({
            "success": true,
            "data": response_envelope,
        }))
        .unwrap();

        let server = thread::spawn(move || {
            let (mut registration_stream, _) = listener.accept().unwrap();
            let registration = read_request(&mut registration_stream);
            let registration_header_end = registration
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            assert!(
                String::from_utf8_lossy(&registration[..registration_header_end])
                    .to_ascii_lowercase()
                    .starts_with("post /api/client/users http/1.1")
            );
            write!(
                registration_stream,
                "HTTP/1.1 409 Conflict\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                CLIENT_USER_ALREADY_EXISTS.len(),
                CLIENT_USER_ALREADY_EXISTS
            )
            .unwrap();

            let (mut report_stream, _) = listener.accept().unwrap();
            let report = read_request(&mut report_stream);
            let report_header_end = report
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .unwrap();
            assert!(
                String::from_utf8_lossy(&report[..report_header_end])
                    .to_ascii_lowercase()
                    .starts_with("post /api/client/report http/1.1")
            );
            write!(
                report_stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                report_response.len(),
                report_response
            )
            .unwrap();
        });

        let registration_auth = ClientAuth {
            user_id: DEFAULT_WECHAT_USER_ID.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp: "1784559820".to_owned(),
            nonce: "registration-rebind-nonce".to_owned(),
            signature: String::new(),
        };
        let report_auth = ClientAuth {
            nonce: "report-rebind-nonce".to_owned(),
            ..registration_auth.clone()
        };
        let report_json = fixed_native_wechat_report().to_json_bytes().unwrap();
        let client = Client::builder().no_proxy().build().unwrap();
        let result = send_native_sync_report_auto_signed_with_iv_with_client(
            &client,
            &registration_endpoint,
            &report_endpoint,
            &report_json,
            &registration_auth,
            &report_auth,
            &[0x88; 24],
        )
        .unwrap();
        server.join().unwrap();

        assert_eq!(result.registration.http_status, 409);
        assert_eq!(
            result.registration.response_body,
            CLIENT_USER_ALREADY_EXISTS
        );
        assert_eq!(
            result.report.decrypted_json["requestId"],
            "native-sync-rebind"
        );
    }

    #[test]
    fn client_users_endpoint_is_derived_only_from_report_path() {
        assert_eq!(
            client_users_endpoint_for_report("https://example.test/api/client/report?ignored=1")
                .unwrap(),
            "https://example.test/api/client/users"
        );
        assert!(client_users_endpoint_for_report("https://example.test/other").is_err());
    }

    #[test]
    fn signing_with_fixed_observations_is_native_hex() {
        let canonical =
            "POST\n/api/client/report\nwxid_test\nWECHAT\n1784559820\none-shot-nonce\n{}";
        let signature = native_sign_client_payload_with_observations(
            canonical,
            0x2080_0ea0_8021_5080,
            [0x0000_0000_6a62_fd27, 0x187b, 0],
        );
        assert_eq!(signature.len(), 1344);
        assert!(
            signature
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
        );
        assert_ne!(
            signature,
            native_sign_client_payload_with_observations(
                "POST\n/api/client/report\nwxid_other\nWECHAT\n1784559820\none-shot-nonce\n{}",
                0x2080_0ea0_8021_5080,
                [0x0000_0000_6a62_fd27, 0x187b, 0],
            )
        );
    }

    #[test]
    fn signing_matches_android_clean_native_route_oracle() {
        let canonical = concat!(
            "POST\n",
            "/api/client/report\n",
            "wxid_oracle\n",
            "WECHAT\n",
            "1784559820\n",
            "signature-oracle-nonce\n",
            "{\"v\":3,\"iv\":\"ERERERERERERERERERERERERERERERER\",",
            "\"kid\":\"d8e39774\",\"payload\":\"oracle\",\"tag\":\"oracle\"}"
        );
        let signature = native_sign_client_payload_with_observations(
            canonical,
            0x2080_6ee0_8021_5080,
            [1_784_888_302, 3_126, 0],
        );
        let expected = concat!(
            "44ba6a708ca5ca57bf912fc4b2ccaddbb6d8210f5bd02d13717f053a1a7a375f",
            "5f8727178f93eb33307da0d0b4dab710e6937a9587145f796755d257651b6088",
            "360a7649c13e833e768bcbd646742ccfc3a9c61faf7287f1c948a4729058adc2",
            "34ac5f90fac968042486bb474e1ee315f9f71a63a7d77821832d3e5a1fbf87ed",
            "c87ced8e39709237c9b72eb81bdd04590bae5c4f81e894661a9184b1e93a407a",
            "6742e04c430530bda7ae5ba9d0fe364d839f062b933e2c796c059432d124f28d",
            "904b064a53bccef2f06632d4bfd3e00057c865afd10c0a46f74f7d8757295739",
            "b7ff8d7474391dc6d0a45e16f84b69c0ae40c2db9882dd2057db823e96f0feb9",
            "8c1ec763b3eda28cd0dc93d029cc41e3afe7078c58093274d550671e81b47082",
            "975281cdfa60bda5be0083b280f4233d699fe8e73600f6bb0e88b892cb05cf56",
            "8fffd5f2a2face2af373570ad30c63e1fd868573cbbcb822c7673380facf9e08",
            "d479284810ef8dcaf19b2b0bd3b87a8cc0ab6bb141382f342a5e84334dfb7188",
            "d365c656dbd1e9558fc8203e4faf29ec854e34b1c1cc2b10202d47ad1a137491",
            "c417163dd8fe248bcef2680175d44d2f448a03baec1310aa2bfda543d54f6c2e",
            "0dec0f878674c0c14ddd0e817fd26b3eabfdf68c0b6cc531ff75bd522f700ffe",
            "5f7eaecda2476b216907c24713a625b700b4d070c60baeb20ae57b019a95902c"
        );
        assert_eq!(signature, expected);
    }

    #[test]
    fn signer_timestamps_match_native_monotonic_baseline() {
        assert_eq!(
            native_signer_timestamps(1_784_559_820, 6_367, 6_367),
            [1_784_559_820, 6_367, 0]
        );
        assert_eq!(
            native_signer_timestamps(1_784_559_821, 6_492, 6_367),
            [1_784_559_821, 6_492, 125]
        );
        assert_eq!(
            native_signer_timestamps(1_784_559_821, 6_366, 6_367),
            [1_784_559_821, 6_366, 0]
        );
    }
}

//! Native client-signer stream transforms.
//!
//! This module ports the three reversible byte transforms invoked by
//! `FUN_00eacbd0`: `FUN_00eaebcc`, `FUN_00eaf038`, and `FUN_00eaece0`.
//! Their PRF inputs and the `FUN_00e966a0` stream generator are verified
//! against direct local native calls in the Unidbg harness.

use super::prf;

const STREAM_TABLE: [u8; 256] = [
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
];

const SCHEDULER_TAG: u32 = 0x041f_7202;
const SCHEDULER_KIND_TAGS: [u32; 4] = [0x4e4f_4133, 0x4e4f_4233, 0x4e4f_4333, 0x4e4f_4433];
const TAG_N0F3: u32 = 0x4e4f_4633;
const TAG_NFD3: u32 = 0x4e46_4433;
const TAG_NFH3: u32 = 0x4e46_4833;
const TAG_NFI3: u32 = 0x4e46_4933;
const TAG_N0I3: u32 = 0x4e4f_4933;
const TAG_N0K3: u32 = 0x4e4f_4b33;
const TAG_N0T3: u32 = 0x4e4f_5433;
const TAG_NFR3: u32 = 0x4e46_5233;
const TAG_NSG3: u32 = 0x4e53_4733;
const TAG_NSK3: u32 = 0x4e53_4b33;
const TAG_INNER_MID: u32 = 0x030f_0a00;
const INNER_RECORD_LENGTH: usize = 109;
const INNER_PAYLOAD_LENGTH: usize = 141;

/// Static 64-byte root emitted by native `FUN_00e9bfac`.
pub const DEFAULT_SIGNER_ROOT: &[u8] =
    b"22521becd14f33ebf6ad59aeec80f6354cff1e451a69703d03b7a7cc0243c6fa";
/// Native signer companion secret used together with [`DEFAULT_SIGNER_ROOT`].
pub const DEFAULT_SIGNER_COMPANION_SECRET: &[u8] =
    b"141da00fe2e426fce669a0d1736e96f4aa0b0d269e20d331ad2fa25904417699";
pub const DEFAULT_SIGNER_KID: &[u8; 8] = b"d8e39774";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ScheduledTransform {
    Xor,
    CenterOut,
    Stride,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ScheduleStep {
    pub transform: ScheduledTransform,
    pub offset: usize,
    pub length: usize,
    pub tag: u32,
}

/// Request-independent fields bound into the signer header's `N0I3` digest.
#[derive(Clone, Copy, Debug)]
pub struct SignerHeaderContext<'a> {
    pub mode: u8,
    pub runtime_flags: u64,
    pub kid: &'a [u8],
    pub timestamps: [u64; 3],
    pub inner_payload: &'a [u8],
}

/// Inputs to the 109-byte inner record built by native `FUN_00e9db38`.
#[derive(Clone, Copy, Debug)]
pub struct SignerInnerRecordContext<'a> {
    pub runtime_flags: u64,
    pub canonical_digest: &'a [u8; 32],
    pub header_nonce: &'a [u8; 16],
    pub kid: &'a [u8],
    /// Number of accepted native anti-tamper samples, capped at 32 by native.
    pub accepted_samples: u32,
}

/// Inputs supplied by `FUN_00e9dfd0` to the normal `FUN_00eacbd0` packet
/// constructor after its time and anti-tamper observations have been made.
#[derive(Clone, Copy, Debug)]
pub struct SignerPacketContext<'a> {
    pub root: &'a [u8],
    pub companion_secret: &'a [u8],
    pub canonical: &'a [u8],
    pub runtime_flags: u64,
    pub mode: u8,
    pub kid: &'a [u8],
    pub timestamps: [u64; 3],
    pub inner_payload: &'a [u8],
}

/// All request-bound inputs gathered by native `FUN_00e9dfd0` before it calls
/// `FUN_00e99c34` and then the packet constructor.
#[derive(Clone, Copy, Debug)]
pub struct NativeSignerContext<'a> {
    pub root: &'a [u8],
    pub companion_secret: &'a [u8],
    pub canonical: &'a [u8],
    pub runtime_flags: u64,
    pub mode: u8,
    pub kid: &'a [u8],
    pub timestamps: [u64; 3],
    pub accepted_samples: u32,
}

/// Raw signer output before the native lowercase-hex encoding step.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SignerPacket {
    pub nonce: [u8; 16],
    pub packet: Vec<u8>,
    /// Native `FUN_00eacbd0` appends the first 16 bytes of `N0T3`.
    pub authentication_tag: [u8; 16],
}

/// Derives the 32-byte signer key from the two native signing roots.
///
/// This is the `N0K3` call in `FUN_00eacbd0`; the first root comes from the
/// signing-key decryptor and the second is the companion native secret.
pub fn signer_packet_key(root: &[u8], companion_secret: &[u8], kid: &[u8]) -> [u8; 32] {
    prf::prf(TAG_N0K3, &[root, companion_secret, kid])
}

/// Derives the normal-path signer nonce from the request-bound `N0F3` seed.
///
/// Native uses the first 16 bytes as the envelope nonce when its random source
/// succeeds. A random-source failure skips this derivation and follows a
/// separate fallback path, which callers must model explicitly.
pub fn signer_nonce(
    root: &[u8],
    canonical: &[u8],
    runtime_flags: u64,
    mode: u8,
    kid: &[u8],
) -> [u8; 16] {
    let flags = runtime_flags.to_le_bytes();
    let seed = prf::prf(TAG_N0F3, &[root, canonical, &flags, &[mode], kid]);
    seed[..16].try_into().unwrap()
}

/// Builds the keyed `N0F3` filler before the first signer transform.
///
/// `FUN_00eacbd0` expands this request-bound stream in 32-byte blocks and
/// truncates its last block to the allocated packet size.
pub fn initial_packet_filler(
    key: &[u8; 32],
    nonce: &[u8; 16],
    canonical: &[u8],
    runtime_flags: u64,
    mode: u8,
    packet_length: usize,
) -> Vec<u8> {
    let flags = runtime_flags.to_le_bytes();
    let mut packet = Vec::with_capacity(packet_length);
    let mut counter = 0u64;
    while packet.len() < packet_length {
        let counter_bytes = counter.to_le_bytes();
        let block = prf::prf(
            TAG_N0F3,
            &[key, nonce, canonical, &flags, &[mode], &counter_bytes],
        );
        let remaining = packet_length - packet.len();
        packet.extend_from_slice(&block[..remaining.min(block.len())]);
        counter = counter.wrapping_add(1);
    }
    packet
}

/// Applies the full-packet `eaebcc` pass that precedes native header writing.
pub fn apply_initial_packet_transform(packet: &mut [u8], key: &[u8; 32], nonce: &[u8; 16]) {
    transform_xor_keyed(packet, key, nonce, 0x1c1f_7500);
}

/// Computes the 32-byte `N0T3` suffix authentication value.
pub fn final_packet_mac(key: &[u8; 32], nonce: &[u8; 16], packet: &[u8]) -> [u8; 32] {
    prf::prf(TAG_N0T3, &[key, nonce, packet])
}

/// Computes the request digest written into the signer header before `N0I3`.
pub fn canonical_header_digest(canonical: &[u8]) -> [u8; 32] {
    prf::prf(TAG_NFH3, &[canonical])
}

/// Derives the request-bound 16-byte nonce stored in the inner anti-tamper
/// record. Native computes this `NFH3` value after a successful random-source
/// check, then retains its first 16 bytes rather than the random bytes.
pub fn signer_inner_header_nonce(
    root: &[u8],
    canonical: &[u8],
    runtime_flags: u64,
    mode: u8,
    kid: &[u8],
) -> [u8; 16] {
    let flags = runtime_flags.to_le_bytes();
    prf::prf(TAG_NFH3, &[root, canonical, &flags, &[mode], kid])[..16]
        .try_into()
        .unwrap()
}

/// Computes the 32-byte `N0I3` header digest.
///
/// The 109-byte inner record is built by `FUN_00e9db38` and passed with its
/// 32-byte prefix as `inner_payload`; serializing that record is intentionally
/// separate because it includes independently recovered runtime checks.
pub fn signer_header_digest(
    key: &[u8; 32],
    nonce: &[u8; 16],
    canonical_digest: &[u8; 32],
    context: SignerHeaderContext<'_>,
) -> [u8; 32] {
    let flags = context.runtime_flags.to_le_bytes();
    let payload_length = u32::try_from(context.inner_payload.len())
        .expect("native signer header payload length fits u32")
        .to_le_bytes();
    let timestamp0 = context.timestamps[0].to_le_bytes();
    let timestamp1 = context.timestamps[1].to_le_bytes();
    let timestamp2 = context.timestamps[2].to_le_bytes();
    prf::prf(
        TAG_N0I3,
        &[
            key,
            nonce,
            &[context.mode],
            &flags,
            context.kid,
            &payload_length,
            &timestamp0,
            &timestamp1,
            &timestamp2,
            canonical_digest,
            context.inner_payload,
        ],
    )
}

/// Reproduces the fixed-length anti-tamper record consumed by the `N0I3`
/// header digest. The separate 32-byte prefix is made in `FUN_00e9dfd0`.
pub fn signer_inner_record(context: SignerInnerRecordContext<'_>) -> [u8; INNER_RECORD_LENGTH] {
    let flags = context.runtime_flags.to_le_bytes();
    let masked_flags = signer_flag_mask(context.runtime_flags).to_le_bytes();
    let canonical_hex = lowercase_hex(context.canonical_digest);
    let empty: [u8; 0] = [];
    let nfd3 = prf::prf(TAG_NFD3, &[&empty]);
    let nfi3 = prf::prf(
        TAG_NFI3,
        &[
            &flags,
            &masked_flags,
            &canonical_hex,
            &empty,
            context.header_nonce,
            context.kid,
        ],
    );
    let mid = prf::prf(
        TAG_INNER_MID,
        &[&flags, &masked_flags, &canonical_hex, &nfd3, context.kid],
    );
    let nfr3 = prf::prf(
        TAG_NFR3,
        &[
            &flags,
            &masked_flags,
            &canonical_hex,
            &empty,
            &nfd3,
            &nfi3,
            context.header_nonce,
            context.kid,
        ],
    );

    let mut record = [0u8; INNER_RECORD_LENGTH];
    record[..4].copy_from_slice(&[0xf3, b'R', b'P', b'3']);
    record[4] = 3;
    record[5..21].copy_from_slice(context.header_nonce);
    record[21..53].copy_from_slice(&nfd3);
    record[53..69].copy_from_slice(&nfi3[..16]);
    record[69..85].copy_from_slice(&nfr3[..16]);
    record[85..101].copy_from_slice(&mid[..16]);
    record[105..].copy_from_slice(&inner_record_checksum(context).to_le_bytes());
    record
}

/// Derives the intermediate `NSK3` value used for the inner-record prefix.
pub fn signer_inner_prefix_key(root: &[u8], companion_secret: &[u8], kid: &[u8]) -> [u8; 32] {
    prf::prf(TAG_NSK3, &[root, companion_secret, kid])
}

/// Derives the 32-byte `NSG3` prefix prepended to the inner record.
pub fn signer_inner_prefix(
    root: &[u8],
    companion_secret: &[u8],
    canonical: &[u8],
    kid: &[u8],
) -> [u8; 32] {
    let prefix_key = signer_inner_prefix_key(root, companion_secret, kid);
    prf::prf(TAG_NSG3, &[&prefix_key, canonical, kid])
}

/// Builds the 141-byte payload bound by the signer's `N0I3` header digest.
pub fn signer_inner_payload(
    root: &[u8],
    companion_secret: &[u8],
    canonical: &[u8],
    record_context: SignerInnerRecordContext<'_>,
) -> [u8; INNER_PAYLOAD_LENGTH] {
    let prefix = signer_inner_prefix(root, companion_secret, canonical, record_context.kid);
    let record = signer_inner_record(record_context);
    let mut payload = [0u8; INNER_PAYLOAD_LENGTH];
    payload[..32].copy_from_slice(&prefix);
    payload[32..].copy_from_slice(&record);
    payload
}

/// Builds the normal signer body and its appended 16-byte `N0T3` tag.
pub fn build_signer_packet(context: SignerPacketContext<'_>) -> SignerPacket {
    let (key, nonce, mut packet) = prepare_signer_packet_body(context);
    apply_scheduler(&mut packet, &key, &nonce);
    let authentication_tag: [u8; 16] = final_packet_mac(&key, &nonce, &packet)[..16]
        .try_into()
        .unwrap();
    SignerPacket {
        nonce,
        packet,
        authentication_tag,
    }
}

/// Reproduces native `FUN_00e9dfd0` after the caller has collected its clock
/// and integrity observations. The `Q` route prefixes the 109-byte record to
/// form a 141-byte payload; fallback mode `0xa6` passes the bare 109-byte
/// record to `FUN_00eacbd0`.
pub fn build_native_signer_packet(context: NativeSignerContext<'_>) -> SignerPacket {
    let canonical_digest = canonical_header_digest(context.canonical);
    let header_nonce = signer_inner_header_nonce(
        context.root,
        context.canonical,
        context.runtime_flags,
        context.mode,
        context.kid,
    );
    let record_context = SignerInnerRecordContext {
        runtime_flags: context.runtime_flags,
        canonical_digest: &canonical_digest,
        header_nonce: &header_nonce,
        kid: context.kid,
        accepted_samples: context.accepted_samples,
    };
    let inner_payload = if context.mode == 0xa6 {
        signer_inner_record(record_context).to_vec()
    } else {
        signer_inner_payload(
            context.root,
            context.companion_secret,
            context.canonical,
            record_context,
        )
        .to_vec()
    };
    build_signer_packet(SignerPacketContext {
        root: context.root,
        companion_secret: context.companion_secret,
        canonical: context.canonical,
        runtime_flags: context.runtime_flags,
        mode: context.mode,
        kid: context.kid,
        timestamps: context.timestamps,
        inner_payload: &inner_payload,
    })
}

/// Native `FUN_00eaf6d0` encodes the raw packet as lowercase hexadecimal.
pub fn encode_signer_packet(packet: &SignerPacket) -> String {
    let mut raw = Vec::with_capacity(16 + packet.packet.len() + 16);
    raw.extend_from_slice(&packet.nonce);
    raw.extend_from_slice(&packet.packet);
    raw.extend_from_slice(&packet.authentication_tag);
    String::from_utf8(lowercase_hex(&raw)).expect("hex encoding is valid UTF-8")
}

/// Builds and lowercase-hex encodes the native `FUN_00e9dfd0` signature.
pub fn sign_native_canonical(context: NativeSignerContext<'_>) -> String {
    encode_signer_packet(&build_native_signer_packet(context))
}

fn prepare_signer_packet_body(context: SignerPacketContext<'_>) -> ([u8; 32], [u8; 16], Vec<u8>) {
    assert!(
        context.inner_payload.len() <= 0x160,
        "the recovered normal signer path supports payloads up to 352 bytes"
    );
    assert_eq!(context.kid.len(), 8, "native signer KID is eight bytes");

    let key = signer_packet_key(context.root, context.companion_secret, context.kid);
    let nonce = signer_nonce(
        context.root,
        context.canonical,
        context.runtime_flags,
        context.mode,
        context.kid,
    );
    let packet_length = 0x1e0 + usize::from(packet_length_selector(context.mode, &nonce)) * 0xa0;
    let mut packet = initial_packet_filler(
        &key,
        &nonce,
        context.canonical,
        context.runtime_flags,
        context.mode,
        packet_length,
    );
    apply_initial_packet_transform(&mut packet, &key, &nonce);

    let canonical_digest = canonical_header_digest(context.canonical);
    let header_digest = signer_header_digest(
        &key,
        &nonce,
        &canonical_digest,
        SignerHeaderContext {
            mode: context.mode,
            runtime_flags: context.runtime_flags,
            kid: context.kid,
            timestamps: context.timestamps,
            inner_payload: context.inner_payload,
        },
    );
    write_outer_header(
        &mut packet,
        context,
        &nonce,
        &canonical_digest,
        &header_digest,
    );
    (key, nonce, packet)
}

fn packet_length_selector(mode: u8, nonce: &[u8; 16]) -> u8 {
    if mode == b'Q' {
        nonce[0] & 3
    } else {
        nonce[1] & 1
    }
}

fn write_outer_header(
    packet: &mut [u8],
    context: SignerPacketContext<'_>,
    nonce: &[u8; 16],
    canonical_digest: &[u8; 32],
    header_digest: &[u8; 32],
) {
    let payload_length = u32::try_from(context.inner_payload.len())
        .expect("native signer payload length fits u32")
        .to_le_bytes();
    let flags = context.runtime_flags.to_le_bytes();
    let timestamp0 = context.timestamps[0].to_le_bytes();
    let timestamp1 = context.timestamps[1].to_le_bytes();
    let timestamp2 = context.timestamps[2].to_le_bytes();
    let payload_end = 128 + context.inner_payload.len();
    assert!(
        payload_end <= packet.len(),
        "native signer packet allocation is too short"
    );

    packet[..4].copy_from_slice(&[0x91, b'N', 0xa7, b'3']);
    packet[4] = 3;
    packet[5] = context.mode;
    packet[6] ^= nonce[3].rotate_left(1);
    packet[7] ^= nonce[11].rotate_right(1);
    packet[8..16].copy_from_slice(&flags);
    packet[16] = context.kid.len() as u8;
    packet[17..21].copy_from_slice(&payload_length);
    packet[21..29].copy_from_slice(&timestamp0);
    packet[29..37].copy_from_slice(&timestamp1);
    packet[37..45].copy_from_slice(&timestamp2);
    packet[45..53].copy_from_slice(context.kid);
    packet[61..93].copy_from_slice(canonical_digest);
    packet[93..125].copy_from_slice(header_digest);
    packet[128..payload_end].copy_from_slice(context.inner_payload);
}

/// Selects the native packet route after `FUN_00ea3418` has collected its
/// runtime observation bitset. `Q` is the normal route; `0xa6` is the
/// fallback route with the shorter inner record.
pub fn native_signer_mode(runtime_flags: u64) -> u8 {
    const REQUIRED: u64 = 0x0000_0800_0000_6400;
    if runtime_flags & REQUIRED == REQUIRED || signer_flag_mask(runtime_flags) != 0 {
        0xa6
    } else {
        b'Q'
    }
}

fn signer_flag_mask(runtime_flags: u64) -> u64 {
    let integrity_bits = runtime_flags & 0x0b10_3808_6c00;
    let selector = if runtime_flags & 0x0446_0008 == 0 {
        if runtime_flags & 0x8000_0000_0000_0107 != 0 && integrity_bits != 0 {
            0x8000_0b10_3808_6d07
        } else {
            0x8000_0000_0446_010f
        }
    } else if integrity_bits != 0 {
        0x8000_0b10_3c4e_6d4f
    } else {
        0x8000_0000_0446_014f
    };
    let mut masked = selector & runtime_flags;
    if runtime_flags & 0x80 != 0 && runtime_flags & (selector | 0x0446_000c) != 0 {
        masked |= 0x80;
    }
    if runtime_flags & 0x2000 != 0 && (masked != 0 || runtime_flags & 0x0446_000c != 0) {
        masked |= 0x2000;
    }
    if runtime_flags & 0x23c1_8010_30 != 0 && (masked != 0 || runtime_flags & 0x0446_000c != 0) {
        masked |= runtime_flags & 0x1000;
    }
    masked
}

fn lowercase_hex(input: &[u8]) -> Vec<u8> {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = Vec::with_capacity(input.len() * 2);
    for byte in input {
        output.push(HEX[(byte >> 4) as usize]);
        output.push(HEX[(byte & 0x0f) as usize]);
    }
    output
}

fn inner_record_checksum(context: SignerInnerRecordContext<'_>) -> u32 {
    let masked_flags = signer_flag_mask(context.runtime_flags);
    let raw_popcount = context.runtime_flags.count_ones();
    let masked_popcount = masked_flags.count_ones();
    let samples = context.accepted_samples.min(32);
    let mut checksum = raw_popcount
        .wrapping_mul(0x1d)
        .wrapping_add(masked_popcount.wrapping_mul(0x83))
        .wrapping_add(samples.wrapping_mul(0x11));
    if masked_flags & 0x3000 != 0 {
        checksum = checksum.wrapping_add(0xd3);
    }
    if (context.runtime_flags as u32) & 0x30 == 0x30 {
        checksum = checksum.wrapping_add(0x2b);
    }
    checksum
}

/// Derives the 32-byte state that controls the 18-step signer scheduler in
/// native `FUN_00eacbd0`.
pub fn scheduler_seed(key: &[u8; 32], nonce: &[u8; 16], packet_length: usize) -> [u8; 32] {
    let length = (packet_length as u64).to_le_bytes();
    prf::prf(SCHEDULER_TAG, &[key, nonce, &length])
}

/// Reproduces the 18 post-header transforms selected by `FUN_00eacbd0`.
///
/// The native function applies an earlier full-buffer XOR before this routine.
/// Callers apply that first transform separately, then invoke the keyed
/// transform represented by each returned step on `packet[offset..offset +
/// length]`.
pub fn scheduler_steps(
    key: &[u8; 32],
    nonce: &[u8; 16],
    packet_length: usize,
) -> Vec<ScheduleStep> {
    assert!(
        packet_length >= 64,
        "native signer packets are at least 64 bytes"
    );

    let seed = scheduler_seed(key, nonce, packet_length);
    let mut seen = [false; 18];
    let mut slot = usize::from(seed[0]) % seen.len();
    let mut word = u32::from_le_bytes([seed[1], seed[2], seed[3], seed[4]]);
    let mut steps = Vec::with_capacity(seen.len());

    for iteration in 0..seen.len() {
        if seen[slot] {
            for retry in 0..seen.len() {
                slot = (slot + (((word ^ iteration as u32 ^ retry as u32) % 17) + 1) as usize)
                    % seen.len();
                if !seen[slot] || retry + 1 == seen.len() {
                    break;
                }
            }
        }

        let tag = (slot as u32).wrapping_mul(0x1656_67b1)
            ^ (iteration as u32).wrapping_mul(0x27d4_eb2d)
            ^ SCHEDULER_KIND_TAGS[slot & 3]
            ^ word.rotate_right(((slot as u32 & 0x0f).wrapping_neg()) & 0x1f);
        seen[slot] = true;

        let (transform, offset, length) = match slot % 6 {
            0 => (ScheduledTransform::Xor, 0, packet_length),
            1 => (ScheduledTransform::CenterOut, 0, packet_length),
            2 => (ScheduledTransform::Stride, 0, packet_length),
            kind => {
                let range_length = usize::from(u16::from_le_bytes([
                    seed[(slot + iteration) & 0x1f],
                    seed[(slot * 3 + iteration + 7) & 0x1f],
                ])) % (packet_length - 63)
                    + 64;
                let offset = usize::from(u16::from_le_bytes([
                    seed[(slot * 5 + iteration + 13) & 0x1f],
                    seed[(slot * 7 + iteration + 19) & 0x1f],
                ])) % (packet_length - range_length + 1);
                let transform = match kind {
                    3 => ScheduledTransform::Xor,
                    4 => ScheduledTransform::CenterOut,
                    5 => ScheduledTransform::Stride,
                    _ => unreachable!(),
                };
                (transform, offset, range_length)
            }
        };
        steps.push(ScheduleStep {
            transform,
            offset,
            length,
            tag,
        });

        let rotation = (u32::from(seed[(slot + iteration) & 0x1f] & 0x0f))
            .wrapping_neg()
            .wrapping_sub(3)
            & 0x1f;
        word = word
            .rotate_right(rotation)
            .wrapping_add((slot as u32).wrapping_mul(0x045d_9f3b) ^ 0x9e37_79b9);
        slot = (slot + (((word ^ u32::from(seed[11 + slot])) % 17) + 1) as usize) % seen.len();
    }

    steps
}

/// Applies the 18 post-header signer transforms in native order.
pub fn apply_scheduler(packet: &mut [u8], key: &[u8; 32], nonce: &[u8; 16]) {
    for step in scheduler_steps(key, nonce, packet.len()) {
        let range = &mut packet[step.offset..step.offset + step.length];
        match step.transform {
            ScheduledTransform::Xor => transform_xor_keyed(range, key, nonce, step.tag),
            ScheduledTransform::CenterOut => {
                transform_center_out_keyed(range, key, nonce, step.tag)
            }
            ScheduledTransform::Stride => transform_stride_keyed(range, key, nonce, step.tag),
        }
    }
}

/// `FUN_00e966a0` state machine. Native uses a 0x58-byte packed state;
/// keeping that layout makes its captured state vectors directly testable.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SignStream {
    state: [u8; 0x58],
}

impl SignStream {
    pub fn new(seed: [u8; 32], length: usize, tag: u32) -> Self {
        let mut state = [0u8; 0x58];
        state[..32].copy_from_slice(&seed);
        let mut sampled_seed = 0u64;
        for (shift, offset) in [0usize, 5, 9, 13, 17, 21, 25, 31].into_iter().enumerate() {
            sampled_seed |= (seed[offset] as u64) << (shift * 8);
        }
        let initial = (length as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15)
            ^ ((tag as u64) << 29)
            ^ sampled_seed;
        state[0x40..0x48].copy_from_slice(&initial.to_le_bytes());
        state[0x50..0x58].copy_from_slice(&32u64.to_le_bytes());
        Self { state }
    }

    #[cfg(test)]
    fn from_state(state: [u8; 0x58]) -> Self {
        Self { state }
    }

    #[cfg(test)]
    fn state(&self) -> [u8; 0x58] {
        self.state
    }

    pub fn next_byte(&mut self) -> u8 {
        let cursor = self.word(0x50);
        if cursor > 31 {
            self.refill();
        }
        let cursor = self.word(0x50) as usize;
        let output = self.state[0x20 + cursor];
        self.set_word(0x50, cursor as u64 + 1);
        output
    }

    fn refill(&mut self) {
        let counter = self.word(0x48);
        let counter_low = counter as usize;
        let mut x = self.word(0x40) ^ counter.rotate_right(47);
        for (shift, offset) in [0usize, 3, 7, 11, 13, 17, 19, 23].into_iter().enumerate() {
            x ^= (self.state[(counter_low + offset) & 0x1f] as u64) << (shift * 8);
        }

        let mut seed_index = 11usize;
        for index in 0..32usize {
            let rotation_source = self.state[(counter_low + index) & 0x1f];
            let table_source = self.state[(counter_low + index * 5) & 0x1f];
            x ^= x << 7;
            let seed_byte = self.state[seed_index & 0x1f];
            seed_index += 7;
            let mixed = (x ^ (x >> 9)).wrapping_mul(0xd6e8_feb8_6659_fd93);
            let rotate = ((!rotation_source | 0x20) & 0x3f) as u32;
            x = mixed.rotate_right(rotate);
            let table_index =
                table_source ^ (index as u8).wrapping_mul(99) ^ x as u8 ^ (x >> 17) as u8;
            self.state[0x20 + index] =
                seed_byte.rotate_left((index & 7) as u32) ^ STREAM_TABLE[table_index as usize];
        }
        self.set_word(0x40, counter.wrapping_mul(0x45d9_f3b2_7d4e_b2d) ^ x);
        self.set_word(0x48, counter.wrapping_add(1));
        self.set_word(0x50, 0);
    }

    fn word(&self, offset: usize) -> u64 {
        u64::from_le_bytes(self.state[offset..offset + 8].try_into().unwrap())
    }

    fn set_word(&mut self, offset: usize, value: u64) {
        self.state[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
    }
}

/// Native `FUN_00eaebcc`: sequential XOR with the signer stream.
///
/// `stream_seed` is the 32-byte output of the preceding keyed
/// `FUN_00ea6f30` call. The keyed PRF is recovered separately because its
/// caller-provided master context differs from the global config PRF context.
pub fn transform_xor(data: &mut [u8], stream_seed: [u8; 32], tag: u32) {
    let mut stream = SignStream::new(stream_seed, data.len(), tag);
    for byte in data {
        *byte ^= stream.next_byte();
    }
}

/// Native `FUN_00eaebcc`, including its accepted-context PRF derivation.
pub fn transform_xor_keyed(data: &mut [u8], key: &[u8; 32], nonce: &[u8; 16], tag: u32) {
    let length = (data.len() as u64).to_le_bytes();
    let empty = [];
    let stream_seed = prf::prf(tag, &[key, nonce, &length, &empty]);
    transform_xor(data, stream_seed, tag);
}

/// Native `FUN_00eaf038`: a coprime center-out permutation plus XOR stream.
pub fn transform_center_out(data: &mut [u8], selector: [u8; 32], stream_seed: [u8; 32], tag: u32) {
    if data.is_empty() {
        return;
    }
    let (step, start) = selector_parameters(&selector, data.len(), 0);
    let mut stream = SignStream::new(stream_seed, data.len(), tag);
    let len = data.len();
    for index in 0..len {
        let center = if index & 1 == 0 {
            (len + len / 2 - ((index / 2 + 1) % len)) % len
        } else {
            (index / 2 + len / 2) % len
        };
        let position = (start + center.wrapping_mul(step)) % len;
        data[position] ^= selector[(position + index) & 0x1f]
            ^ (index as u8).wrapping_mul(0x5d)
            ^ (position as u8).rotate_left((index & 7) as u32)
            ^ stream.next_byte();
    }
}

/// Native `FUN_00eaf038`, including selector and stream PRF derivation.
pub fn transform_center_out_keyed(data: &mut [u8], key: &[u8; 32], nonce: &[u8; 16], tag: u32) {
    if data.is_empty() {
        return;
    }
    let length = (data.len() as u64).to_le_bytes();
    let selector = prf::prf(tag ^ 0x3150_5233, &[key, nonce, &length]);
    let stream_seed = prf::prf(tag, &[key, nonce, &length, &selector]);
    transform_center_out(data, selector, stream_seed, tag);
}

/// Native `FUN_00eaece0`: a coprime forward permutation plus XOR stream.
pub fn transform_stride(data: &mut [u8], selector: [u8; 32], stream_seed: [u8; 32], tag: u32) {
    if data.is_empty() {
        return;
    }
    // This transform reads the two selector words at offsets 8 and 10.
    let (step, start) = selector_parameters(&selector, data.len(), 8);
    let mut stream = SignStream::new(stream_seed, data.len(), tag);
    let len = data.len();
    for index in 0..len {
        let position = (start + index.wrapping_mul(step)) % len;
        let rotated_position = (position as u8).rotate_right((index & 7) as u32);
        data[position] ^= stream.next_byte()
            ^ (index as u8).wrapping_mul(0xa7)
            ^ rotated_position.wrapping_add(selector[(position ^ index) & 0x1f]);
    }
}

/// Native `FUN_00eaece0`, including selector and stream PRF derivation.
pub fn transform_stride_keyed(data: &mut [u8], key: &[u8; 32], nonce: &[u8; 16], tag: u32) {
    if data.is_empty() {
        return;
    }
    let length = (data.len() as u64).to_le_bytes();
    let selector = prf::prf(tag ^ 0x5354_333d, &[key, nonce, &length]);
    let stream_seed = prf::prf(tag, &[key, nonce, &length, &selector]);
    transform_stride(data, selector, stream_seed, tag);
}

fn selector_parameters(selector: &[u8; 32], length: usize, offset: usize) -> (usize, usize) {
    let mut step = usize::from(u16::from_le_bytes(
        selector[offset..offset + 2].try_into().unwrap(),
    )) % length
        | 1;
    while gcd(step, length) != 1 {
        step = (step + 2) % length;
        if step < 2 {
            step = 1;
        }
    }
    let start = usize::from(u16::from_le_bytes(
        selector[offset + 2..offset + 4].try_into().unwrap(),
    )) % length;
    (step, start)
}

fn gcd(mut left: usize, mut right: usize) -> usize {
    while right != 0 {
        (left, right) = (right, left % right);
    }
    left
}

#[cfg(test)]
mod tests {
    use super::{
        DEFAULT_SIGNER_COMPANION_SECRET, DEFAULT_SIGNER_KID, DEFAULT_SIGNER_ROOT,
        NativeSignerContext, ScheduledTransform, SignStream, SignerHeaderContext,
        SignerInnerRecordContext, SignerPacketContext, apply_initial_packet_transform,
        apply_scheduler, build_native_signer_packet, build_signer_packet, canonical_header_digest,
        encode_signer_packet, initial_packet_filler, native_signer_mode,
        prepare_signer_packet_body, scheduler_seed, scheduler_steps, sign_native_canonical,
        signer_header_digest, signer_inner_header_nonce, signer_inner_payload,
        signer_inner_prefix_key, signer_inner_record, signer_nonce, signer_packet_key,
        transform_center_out, transform_stride, transform_xor, transform_xor_keyed,
    };

    fn hex(input: &str) -> Vec<u8> {
        (0..input.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&input[index..index + 2], 16).unwrap())
            .collect()
    }

    fn fixture() -> Vec<u8> {
        let mut data = vec![0u8; 73];
        for (index, byte) in data.iter_mut().enumerate() {
            *byte = (index as u8).wrapping_mul(13).wrapping_add(0x29);
        }
        data
    }

    fn normal_oracle_canonical() -> Vec<u8> {
        concat!(
            "POST\n",
            "/api/client/report\n",
            "wxid_oracle\n",
            "WECHAT\n",
            "1784559820\n",
            "signature-oracle-nonce\n",
            "{\"v\":3,\"iv\":\"ERERERERERERERERERERERERERERERER\",",
            "\"kid\":\"d8e39774\",\"payload\":\"oracle\",\"tag\":\"oracle\"}"
        )
        .as_bytes()
        .to_vec()
    }

    fn normal_oracle_inner_payload() -> Vec<u8> {
        hex(
            "77a4cb31993038b85c55764dfe18ccb9cf90a2c828ef7ed0dfab8a81cd5dc774\
             f35250330354206eed4797c922e987bda092498788c7a36c78356a046aa3d68\
             ddaa5362645ee49fc7cad53f888ee58f44adeb66127aa03c0f2e7896a02a0883\
             2ba2306cb3e121e559f696b52e9f92ca7fa161d3c83af89750de02b13f3a5909\
             44e4c257eac0000000079010000",
        )
    }

    fn traced_fallback_oracle_record() -> Vec<u8> {
        hex(
            "f352503303a645935c2b95be3f7073fd6442c52d43c7a36c78356a046aa3d68\
             ddaa5362645ee49fc7cad53f888ee58f44adeb6612752153ca322d2ec39be250\
             dc99dc51b76a868e92b9244e37882c7add52afd4afcd922143eac4825d7ce712\
             f94f47a0d870000000072060000",
        )
    }

    #[test]
    fn signer_nonce_key_and_filler_match_normal_native_oracle() {
        let root = b"22521becd14f33ebf6ad59aeec80f6354cff1e451a69703d03b7a7cc0243c6fa";
        let companion = b"141da00fe2e426fce669a0d1736e96f4aa0b0d269e20d331ad2fa25904417699";
        let kid = b"d8e39774";
        let canonical = normal_oracle_canonical();
        let flags = 0x2080_0ea0_8021_5080u64;

        let key = signer_packet_key(root, companion, kid);
        let expected_key: [u8; 32] =
            hex("13cde5b45932835539643638dc60c4862a61c44ae4d07387b2ba70d3e47f50a7")
                .try_into()
                .unwrap();
        assert_eq!(key, expected_key);

        let nonce = signer_nonce(root, &canonical, flags, 0x51, kid);
        let expected_nonce: [u8; 16] = hex("2964c0891505b67402d8bf4551c0a606").try_into().unwrap();
        assert_eq!(nonce, expected_nonce);

        let mut filler = initial_packet_filler(&key, &nonce, &canonical, flags, 0x51, 640);
        assert_eq!(
            &filler[..64],
            hex(
                "739409044ba46e78bdba175f9cd3b0a1838105e1e0c43a959d81a474bd6eb90f\
                 ce2e1c750917795bee98b8ada84ce4ab882f467d560e2075919962f0fdcd2c2e"
            )
        );
        assert_eq!(
            &filler[576..],
            hex(
                "9e5debc563379befbefaae6239791743b16324b4f181380e75293b26f8fd05e6\
                 7aba207c5a31bf84b25201d2b55cbe07255aaae08022a1d2c20b2332c42db831"
            )
        );

        apply_initial_packet_transform(&mut filler, &key, &nonce);
        assert_eq!(
            &filler[..32],
            hex("b7d0d4a6abc0b77a3e083c8f6a6f544c552f7e577b5fc780aa49270502f3d6fa")
        );
    }

    #[test]
    fn header_digests_match_normal_native_oracle() {
        let root = b"22521becd14f33ebf6ad59aeec80f6354cff1e451a69703d03b7a7cc0243c6fa";
        let companion = b"141da00fe2e426fce669a0d1736e96f4aa0b0d269e20d331ad2fa25904417699";
        let kid = b"d8e39774";
        let canonical = normal_oracle_canonical();
        let runtime_flags = 0x2080_0ea0_8021_5080u64;
        let key = signer_packet_key(root, companion, kid);
        let nonce = signer_nonce(root, &canonical, runtime_flags, 0x51, kid);
        let inner_payload = normal_oracle_inner_payload();
        assert_eq!(inner_payload.len(), 141);

        let canonical_digest = canonical_header_digest(&canonical);
        let expected_canonical_digest: [u8; 32] =
            hex("1a25618ee24cab0df08aa18fbbcd8e9ae42e0254d8e4370329ae64dcdd893c58")
                .try_into()
                .unwrap();
        assert_eq!(canonical_digest, expected_canonical_digest);

        let digest = signer_header_digest(
            &key,
            &nonce,
            &canonical_digest,
            SignerHeaderContext {
                mode: 0x51,
                runtime_flags,
                kid,
                timestamps: [0x0000_0000_6a62_fd27, 0x0000_0000_0000_187b, 0],
                inner_payload: &inner_payload,
            },
        );
        let expected_digest: [u8; 32] =
            hex("90c276c44ab1627bdbed513c223c74cbe420c8407ccc4f7c41f1ab74d315cd76")
                .try_into()
                .unwrap();
        assert_eq!(digest, expected_digest);
    }

    #[test]
    fn packet_builder_matches_complete_normal_native_oracle() {
        let root = b"22521becd14f33ebf6ad59aeec80f6354cff1e451a69703d03b7a7cc0243c6fa";
        let companion = b"141da00fe2e426fce669a0d1736e96f4aa0b0d269e20d331ad2fa25904417699";
        let kid = b"d8e39774";
        let canonical = normal_oracle_canonical();
        let inner_payload = normal_oracle_inner_payload();
        assert_eq!(root, DEFAULT_SIGNER_ROOT);
        assert_eq!(companion, DEFAULT_SIGNER_COMPANION_SECRET);
        assert_eq!(kid, DEFAULT_SIGNER_KID);
        let native_context = NativeSignerContext {
            root,
            companion_secret: companion,
            canonical: &canonical,
            runtime_flags: 0x2080_0ea0_8021_5080,
            mode: b'Q',
            kid,
            timestamps: [0x0000_0000_6a62_fd27, 0x0000_0000_0000_187b, 0],
            accepted_samples: 0,
        };
        assert_eq!(
            signer_inner_header_nonce(root, &canonical, native_context.runtime_flags, b'Q', kid),
            hex("54206eed4797c922e987bda092498788").as_slice()
        );
        let context = SignerPacketContext {
            root,
            companion_secret: companion,
            canonical: &canonical,
            runtime_flags: 0x2080_0ea0_8021_5080,
            mode: b'Q',
            kid,
            timestamps: [0x0000_0000_6a62_fd27, 0x0000_0000_0000_187b, 0],
            inner_payload: &inner_payload,
        };

        let (_, nonce, prepared) = prepare_signer_packet_body(context);
        assert_eq!(nonce, hex("2964c0891505b67402d8bf4551c0a606").as_slice());
        assert_eq!(prepared.len(), 640);
        assert_eq!(
            &prepared[..128],
            hex("914ea7330351a4d880502180a00e8020\
                 088d00000027fd626a000000007b1800\
                 00000000000000000000000000643865\
                 33393737344d43bf965645b46c1a2561\
                 8ee24cab0df08aa18fbbcd8e9ae42e02\
                 54d8e4370329ae64dcdd893c5890c276\
                 c44ab1627bdbed513c223c74cbe420c8\
                 407ccc4f7c41f1ab74d315cd766aacb7",)
        );
        assert_eq!(&prepared[128..269], inner_payload.as_slice());

        let built = build_signer_packet(context);
        assert_eq!(build_native_signer_packet(native_context), built);
        let mut raw = built.nonce.to_vec();
        raw.extend_from_slice(&built.packet);
        raw.extend_from_slice(&built.authentication_tag);
        assert_eq!(
            raw,
            hex("2964c0891505b67402d8bf4551c0a606\
                 f0cca0316b5f097f985cae4d1119a4bbec91cb731387e618bb2c9807f02fc5c8\
                 2fa4acb48f1358c604da5b9742acaf183bfd4b711826a4b1cca784ecd4073a0c\
                 a332956bdaaafb5b7e118fd3bf647d66131a5de78ed5956cde45fabe6d63649c\
                 92ca8889725edf7f394cbfa61782cc9031727fd4f8e1b68457da393c2ca6ef91\
                 3c6601c1897f7328e017907c11067ffc23706de3e9d2dd4f7c666c97f2658d30\
                 a5196b4d5cdba5efb8dff91bd8b433a88d9239442129cad0b1ce3d09b91ff693\
                 b6038f4c773c3f4c44b10d55a3d81b7ae64635dc73f7894aa1cb4997d0133f0c\
                 1435865b113a714a9d32fad638ebd3e9a2691a5e294cb931fb7f7fcefe3d173f\
                 876b87884966c14afd06dd303c77326aaf4f87bd73ae79a63c10e177ae2d9657\
                 a605f07b6438169990f9a3b1d2641c8bdb77208beab1511e9d80349566c9936\
                 de646fcfd2c81608faca2ee67353e8acf148171eb40945a30515d3c2c881e0f\
                 67c79dd759f367f01a210a1fc8547ea59167dbf1452d0d3e5a226761e641bf50\
                 156c54d30b34a93b6e549fb16aa53f130101c402f62cfe1aa23b5a1edda1009\
                 26d03223c6f10983a7c36d8270a0d90496e4d400d4a22f9a3b6d394c23fafb4\
                 6cbf08b6c682dc628871a0b30a788365f3bb7e59ef1945f83ea71b10c441bd04\
                 b4480f4d6ff27a5752e8258728a63d51448dff84fc9aa8d862a554fa9d35fdc\
                 894093588101f04b9aeda43a15a8a909fd6446711b83ad3551c3a386d1013cab\
                 7a7bd82b10065e92ccdaf2652d865a70e79794e28ad334134e7ff0b54fe2fab\
                 2a3e2a2d63151b3843394d41657bf472f6da619f6ff3ec50ca799bfdedb3f859\
                 29dc83835523d15cac08c687134bc735d3adc1de3b3f0c57348e318241eb826\
                 a8fd714ae1f74467606679d03d44dd138882816",)
        );
        let encoded = sign_native_canonical(native_context);
        assert!(
            encoded
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
        );
        assert_eq!(hex(&encoded), raw);
    }

    #[test]
    fn fallback_record_and_packet_match_traced_native_oracle() {
        let root = DEFAULT_SIGNER_ROOT;
        let companion = DEFAULT_SIGNER_COMPANION_SECRET;
        let kid = DEFAULT_SIGNER_KID;
        let canonical = normal_oracle_canonical();
        let flags = 0x2080_6ee0_8421_5084u64;
        let mode = 0xa6;
        let timestamps = [1_784_890_710, 9_549, 0];
        let canonical_digest = canonical_header_digest(&canonical);
        let header_nonce = signer_inner_header_nonce(root, &canonical, flags, mode, kid);
        let record = signer_inner_record(SignerInnerRecordContext {
            runtime_flags: flags,
            canonical_digest: &canonical_digest,
            header_nonce: &header_nonce,
            kid,
            accepted_samples: 0,
        });

        assert_eq!(record.as_slice(), traced_fallback_oracle_record());

        let native_packet = build_native_signer_packet(NativeSignerContext {
            root,
            companion_secret: companion,
            canonical: &canonical,
            runtime_flags: flags,
            mode,
            kid,
            timestamps,
            accepted_samples: 0,
        });

        let packet = build_signer_packet(SignerPacketContext {
            root,
            companion_secret: companion,
            canonical: &canonical,
            runtime_flags: flags,
            mode,
            kid,
            timestamps,
            inner_payload: &record,
        });
        assert_eq!(
            encode_signer_packet(&packet),
            concat!(
                "d549bf219305f44a754eb44a7c07d29a3e200fcf3b5b71f6b921397e1e86bfc2",
                "5e18ab3757600b48dd4f2fabfcdfed352c51663b5612eab90290658c055d9fe2",
                "ebbcc57dc43c957cb9f1bb1ffae5478b3f5962a4f76c52501da0212b45cd2ba",
                "fb2d4b93c43f791cd31d4377f9db09b1a7f0d94cea22ab6020fdbe1be3547bf",
                "e4e02eeff7ed2cc7d1a13973b56988c4a775b4ef98cff51e741ad62a73947c1",
                "3adba2bc858f79e6c2846ff900a7616433dffbac515cfcbd05836dc5e487c08",
                "199842fe72f6a99c7b5d56dd8dfb625ac2c742de01c7d7ef76d8aac35335c1a",
                "4f315a77e5c293355e41b88cd3f1770e4020e5bb903d274c32178bf6e3b1cad",
                "eef87b426742fe954a88d3a22048224e8b22e062cee6d20a133199dad2e0cba",
                "d378baee206b279b24bd32cf552d180c0f1b691dd284474583543c4f12e6b5c",
                "e2773249fad97493a7db4b73a22355363bb975c50af87285c3e78e14bd044f0",
                "a814c70d1229cd6e30c89e32e528e8e419709c9bd0d411b8e9e3a1eeecb700",
                "8206641b7563e7a9620caf67842ddcd489fb861cbce0c60ae8f6c1f33cf700f",
                "81e090218d3b1dd4e2a951aa857204a180f1e7fbddb414c4c8f6b81fff76ae3",
                "954e2aaf49cf5caeb4bb638905b71e8cc68e363e279d9033d1708ccb22221df",
                "5c8b5e028e0a56f6515af43a008c4045f2a97661ba8821cd450a6fe2b163a8",
                "7512d585fe0a675ffcecd824bfccb18f85c7301bbc62bb3f767d64df9d17e00",
                "9933e37bf7989f7742c5190f4b09622fca8d2a82ce95e9dcc500be5265c3743",
                "03931130f169d0e680c16d53f04b9e1f120107ccfc9aac5f531318bb918efc3",
                "f8cd8afebac6155f04d421472e4e0f6bb0ed6c76cd9404fef0c66299d87c3f9",
                "3ddb4400e6cd6a7c913d1b1cd2a408b9c2b52594e3ae5f6465ccb3627d01b6",
                "042297e9ac540760b6eb69"
            )
        );
        assert_eq!(native_packet, packet);
    }

    #[test]
    fn signer_mode_matches_clean_and_traced_native_routes() {
        // These are offline Unidbg observations used only to validate the
        // recovered selector; neither value is a host/device default.
        assert_eq!(native_signer_mode(0x2080_6ee0_8021_5080), b'Q');
        assert_eq!(native_signer_mode(0x2080_6ee0_8421_5084), 0xa6);
    }

    #[test]
    fn inner_record_matches_normal_native_oracle() {
        let canonical_digest: [u8; 32] =
            hex("1a25618ee24cab0df08aa18fbbcd8e9ae42e0254d8e4370329ae64dcdd893c58")
                .try_into()
                .unwrap();
        let header_nonce: [u8; 16] = hex("54206eed4797c922e987bda092498788").try_into().unwrap();
        let record = signer_inner_record(SignerInnerRecordContext {
            runtime_flags: 0x2080_0ea0_8021_5080,
            canonical_digest: &canonical_digest,
            header_nonce: &header_nonce,
            kid: b"d8e39774",
            accepted_samples: 0,
        });
        let expected: [u8; 109] = hex("f35250330354206eed4797c922e987bda092498788\
             c7a36c78356a046aa3d68ddaa5362645ee49fc7cad53f888ee58f44adeb66127\
             aa03c0f2e7896a02a08832ba2306cb3e\
             121e559f696b52e9f92ca7fa161d3c83\
             af89750de02b13f3a590944e4c257eac\
             0000000079010000")
        .try_into()
        .unwrap();
        assert_eq!(record, expected);
    }

    #[test]
    fn inner_payload_prefix_matches_normal_native_oracle() {
        let root = b"22521becd14f33ebf6ad59aeec80f6354cff1e451a69703d03b7a7cc0243c6fa";
        let companion = b"141da00fe2e426fce669a0d1736e96f4aa0b0d269e20d331ad2fa25904417699";
        let kid = b"d8e39774";
        let canonical = normal_oracle_canonical();
        let canonical_digest = canonical_header_digest(&canonical);
        let header_nonce: [u8; 16] = hex("54206eed4797c922e987bda092498788").try_into().unwrap();

        let expected_prefix_key: [u8; 32] =
            hex("8bbf7549fe5938871d84222cf9b90c78f6a04ee46be28445af68627fae5f6060")
                .try_into()
                .unwrap();
        assert_eq!(
            signer_inner_prefix_key(root, companion, kid),
            expected_prefix_key
        );

        let payload = signer_inner_payload(
            root,
            companion,
            &canonical,
            SignerInnerRecordContext {
                runtime_flags: 0x2080_0ea0_8021_5080,
                canonical_digest: &canonical_digest,
                header_nonce: &header_nonce,
                kid,
                accepted_samples: 0,
            },
        );
        let expected: [u8; 141] = hex(
            "77a4cb31993038b85c55764dfe18ccb9cf90a2c828ef7ed0dfab8a81cd5dc774\
             f35250330354206eed4797c922e987bda092498788c7a36c78356a046aa3d68\
             ddaa5362645ee49fc7cad53f888ee58f44adeb66127aa03c0f2e7896a02a0883\
             2ba2306cb3e121e559f696b52e9f92ca7fa161d3c83af89750de02b13f3a5909\
             44e4c257eac0000000079010000",
        )
        .try_into()
        .unwrap();
        assert_eq!(payload, expected);
    }

    #[test]
    fn stream_matches_direct_native_oracle() {
        let state: [u8; 0x58] = hex(
            "616a737c858e97a0a9b2bbc4cdd6dfe8f1fa030c151e273039424b545d666f78\
             0000000000000000000000000000000000000000000000000000000000000000\
             efcdab896745230107000000000000002000000000000000",
        )
        .try_into()
        .unwrap();
        let expected: [u8; 0x58] = hex(
            "616a737c858e97a0a9b2bbc4cdd6dfe8f1fa030c151e273039424b545d666f78\
             234e6a5d5855a134fc7973ae8a3cfb2e9e75b61becef1c8d893d7354ece3116a\
             6539add363ac0d0008000000000000002000000000000000",
        )
        .try_into()
        .unwrap();
        let mut stream = SignStream::from_state(state);
        for _ in 0..32 {
            stream.next_byte();
        }
        assert_eq!(stream.state(), expected);
    }

    #[test]
    fn xor_transform_matches_direct_native_oracle() {
        let mut data = fixture();
        let stream_seed: [u8; 32] =
            hex("eb6ff09b17b8c53939a9a31222ffb913330c028356675ec79e4d46c188954e2f")
                .try_into()
                .unwrap();
        transform_xor(&mut data, stream_seed, 0x1c1f7500);
        assert_eq!(
            data,
            hex(
                "d60a8cc234788682dcdeba2e3f6c33ca1e34728c9d378922ad7bad3861efc721\
                 821d1634e90848ca93fe2f5f29ce343d1b22523bd3efead60b5fe9e269f518e6\
                 6ed6bb1faf13df6d16"
            )
        );
    }

    #[test]
    fn keyed_xor_matches_full_signer_schedule_first_step() {
        let key: [u8; 32] = hex("13cde5b45932835539643638dc60c4862a61c44ae4d07387b2ba70d3e47f50a7")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 16];
        let mut data = vec![0x11; 640];

        transform_xor_keyed(&mut data, &key, &nonce, 0x1c1f7500);

        assert_eq!(
            data,
            hex(
                "020363c8a696dd0b542e62346fb83ba2998707ed6e5b7973f87de68ac67d6e77\
                 12d9969ae9e117eb6d1e73d1e11e5ca4355680c6744342cc769a1a69c6a408e\
                 4d6078eeba1b8f4185671db66f15b8f0de4a11701f2edfba9fa962a9184d0d02\
                 88654cb96d5c4474bc7904c2d73e281b31786408552e7c8221ca38831024df77\
                 1b87ff24ad3b655b370d5d0ae754d122dc33bafa8248b0223b76b19064ad46b8\
                 e838f0a5db861fdbcca24cebb5d2095201d21d614e7f03297c8a8ba25e46084\
                 bb916d1bda3344929e071810997d869e4fd3f02d8efbf131ec38fe823a486fe\
                 9c27c825ddc42f04a7629eb24c844d63d4fb73090dc948a5d94c58dfccc59828\
                 53b6e9a5ffe4d5c66ae3f18d7d0c128264f969cb26b6e4bb50a4c2c8ee253ae\
                 6c44651dde09485c69d40c12453018f5e5538add0ae411a6c98f9abe2dbf7d7\
                 cbd7eae51f26a0c61723b3b64d18103356ad56e2a8bef4208a638e5af430ac\
                 751bf382569273bf07e8f5954e20d77810d9eba8c74d656cda6978263008911\
                 1645ac918e2bd3916d1c402d96cc81ceb2c6661824f09b83c245bed8387ff5ba\
                 c81917bf65d62e4c858c21e36a6ec02792a4fe3788a8f819b87e5e5cb64b073\
                 0b6b8bbc19315e900be4afd5aa7f98c8208a90ab3f656e987f7bcad26a5d5a0\
                 f6bf8543029e8de5017b1602c2ac079c5e8b9fa86c6596b6abeccc06d1746b6\
                 c763aa35ed816eb4d2f2ce78498edc1360726e24b39cd2b1d4451019a2bfe6e\
                 bf45ae835679d980e5abde04e0af897b1c550120ccfbfd5dabc875342b10fbf\
                 dcf69d30c780e572d01794e122f019764d660468e2563690d82658264bee59b\
                 6686d7726ff401e9afc83215f39c59ebc5e141e50c607b8a5cf1a44d56205e8\
                 ce1e55e657e77e8"
            )
        );
    }

    #[test]
    fn scheduler_matches_native_operation_tag_and_length_trace() {
        let key: [u8; 32] = hex("13cde5b45932835539643638dc60c4862a61c44ae4d07387b2ba70d3e47f50a7")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 16];

        let expected_seed: [u8; 32] =
            hex("19b2e846f3b5b878eaffbb2badc53b2360277f47be6fef7c697fd263a6711fe1")
                .try_into()
                .unwrap();
        assert_eq!(scheduler_seed(&key, &nonce, 640), expected_seed);

        let got: Vec<_> = scheduler_steps(&key, &nonce, 640)
            .into_iter()
            .map(|step| (step.transform, step.offset, step.tag, step.length))
            .collect();
        assert_eq!(
            got,
            vec![
                (ScheduledTransform::CenterOut, 0, 0x7167c89d, 640),
                (ScheduledTransform::Xor, 0, 0x10f36995, 640),
                (ScheduledTransform::CenterOut, 61, 0x155c54e3, 391),
                (ScheduledTransform::Stride, 0, 0xd3d3c682, 640),
                (ScheduledTransform::CenterOut, 0, 0x653e56d9, 640),
                (ScheduledTransform::Xor, 53, 0xa95e2ae0, 454),
                (ScheduledTransform::CenterOut, 0, 0xd821b1be, 640),
                (ScheduledTransform::Stride, 0, 0x7c89da33, 640),
                (ScheduledTransform::CenterOut, 191, 0xf648dbd9, 193),
                (ScheduledTransform::Stride, 0, 0x90ca0594, 640),
                (ScheduledTransform::Xor, 0, 0xd903fd8b, 640),
                (ScheduledTransform::Stride, 178, 0x5e9a0018, 406),
                (ScheduledTransform::Xor, 112, 0x2f5fbf51, 277),
                (ScheduledTransform::CenterOut, 219, 0x7d5eeb68, 388),
                (ScheduledTransform::Xor, 0, 0x8e819c51, 640),
                (ScheduledTransform::Stride, 81, 0x0b286ff8, 283),
                (ScheduledTransform::Stride, 21, 0xe59be4f9, 542),
                (ScheduledTransform::Stride, 215, 0x0cb28488, 341),
            ]
        );
    }

    #[test]
    fn scheduler_matches_native_normal_random_success_trace() {
        let key: [u8; 32] = hex("13cde5b45932835539643638dc60c4862a61c44ae4d07387b2ba70d3e47f50a7")
            .try_into()
            .unwrap();
        // `FUN_00f46964` returned success for this nonce. This is the production
        // path, unlike the all-0x11 random-failure fallback capture.
        let nonce: [u8; 16] = hex("2964c0891505b67402d8bf4551c0a606").try_into().unwrap();

        let expected_seed: [u8; 32] =
            hex("5cc341cfcb6b5fbb858d1bb0da1fce19eb9802328f079e4f3830b87cc2a2d331")
                .try_into()
                .unwrap();
        assert_eq!(scheduler_seed(&key, &nonce, 640), expected_seed);

        let got: Vec<_> = scheduler_steps(&key, &nonce, 640)
            .into_iter()
            .map(|step| (step.transform, step.offset, step.tag, step.length))
            .collect();
        assert_eq!(
            got,
            vec![
                (ScheduledTransform::Stride, 0, 0x4dde8b5e, 640),
                (ScheduledTransform::Stride, 3, 0x9f0d8119, 604),
                (ScheduledTransform::CenterOut, 387, 0xf3fe83a6, 220),
                (ScheduledTransform::CenterOut, 498, 0xe9911999, 100),
                (ScheduledTransform::Xor, 0, 0xdad8c03c, 640),
                (ScheduledTransform::Xor, 383, 0xd0aea067, 251),
                (ScheduledTransform::CenterOut, 0, 0x42787cf8, 640),
                (ScheduledTransform::Xor, 0, 0x6d24aa1d, 640),
                (ScheduledTransform::CenterOut, 43, 0xeaf2a46c, 547),
                (ScheduledTransform::Stride, 366, 0xd7c0706e, 156),
                (ScheduledTransform::Stride, 27, 0xe58c47e6, 561),
                (ScheduledTransform::Stride, 0, 0xa560154c, 640),
                (ScheduledTransform::Xor, 213, 0x9deb940b, 83),
                (ScheduledTransform::CenterOut, 0, 0x378b1494, 640),
                (ScheduledTransform::Xor, 0, 0x65b3e6e0, 640),
                (ScheduledTransform::Xor, 0, 0x42a836fd, 640),
                (ScheduledTransform::CenterOut, 0, 0x651eef0a, 640),
                (ScheduledTransform::Stride, 0, 0x43c697d9, 640),
            ]
        );
    }

    #[test]
    fn scheduler_is_reversible_for_a_full_native_sized_packet() {
        let key: [u8; 32] = hex("13cde5b45932835539643638dc60c4862a61c44ae4d07387b2ba70d3e47f50a7")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 16];
        let mut packet: Vec<u8> = (0..640)
            .map(|index| (index as u8).wrapping_mul(37).wrapping_add(0x5a))
            .collect();
        let original = packet.clone();

        apply_scheduler(&mut packet, &key, &nonce);
        apply_scheduler(&mut packet, &key, &nonce);

        assert_eq!(packet, original);
    }

    #[test]
    fn center_out_transform_matches_direct_native_oracle() {
        let mut data = fixture();
        let selector: [u8; 32] =
            hex("25bee286f5d779eab465ef6158158bda83b6823a0f54390f856f7fa96384d629")
                .try_into()
                .unwrap();
        let stream_seed: [u8; 32] =
            hex("7383f0b4232b2e76635b260414f2999486a9f3746938ce2bfa24515a0f74c7f3")
                .try_into()
                .unwrap();
        transform_center_out(&mut data, selector, stream_seed, 0x6d3a5b17);
        assert_eq!(
            data,
            hex(
                "64c70265c576347b33a1d5283b1e617a2dc54e26a17c278dabd52e891e0416ab\
                 abb266f06cd29a83361bc7b656971db68b5dc58bf02ffcd909016a07dc7a5ab9\
                 3468e70c1f84ac28d5"
            )
        );
    }

    #[test]
    fn stride_transform_matches_direct_native_oracle() {
        let mut data = fixture();
        let selector: [u8; 32] =
            hex("d595ff8b8185974ab6e5b39725b3e64094dabf4e66cb10ae24fef7f295139b0b")
                .try_into()
                .unwrap();
        let stream_seed: [u8; 32] =
            hex("dea0b9109d4766275ac1b9faf2e48f04d13bc94361b3422e9b8c3a1bd2227559")
                .try_into()
                .unwrap();
        transform_stride(&mut data, selector, stream_seed, 0x2e4981c3);
        assert_eq!(
            data,
            hex(
                "9be9cb4001857251c69bcf8a350c1e406c3239f2b81fb7463a52da4a09a1d5ef\
                 e8f92302df70c617dee82fd40f7295ed6f7b67b0ef158a9ff4565a60f30439b9\
                 0e3762d4da3781a252"
            )
        );
    }

    #[test]
    fn signer_transforms_round_trip_boundary_lengths() {
        let xor_seed: [u8; 32] =
            hex("eb6ff09b17b8c53939a9a31222ffb913330c028356675ec79e4d46c188954e2f")
                .try_into()
                .unwrap();
        let center_selector: [u8; 32] =
            hex("25bee286f5d779eab465ef6158158bda83b6823a0f54390f856f7fa96384d629")
                .try_into()
                .unwrap();
        let center_seed: [u8; 32] =
            hex("7383f0b4232b2e76635b260414f2999486a9f3746938ce2bfa24515a0f74c7f3")
                .try_into()
                .unwrap();
        let stride_selector: [u8; 32] =
            hex("d595ff8b8185974ab6e5b39725b3e64094dabf4e66cb10ae24fef7f295139b0b")
                .try_into()
                .unwrap();
        let stride_seed: [u8; 32] =
            hex("dea0b9109d4766275ac1b9faf2e48f04d13bc94361b3422e9b8c3a1bd2227559")
                .try_into()
                .unwrap();

        for length in [0usize, 1, 2, 31, 32, 33, 64, 73, 255] {
            let mut value: Vec<u8> = (0..length)
                .map(|index| (index as u8).wrapping_mul(29).wrapping_add(7))
                .collect();
            let original = value.clone();

            transform_xor(&mut value, xor_seed, 0x1c1f7500);
            transform_xor(&mut value, xor_seed, 0x1c1f7500);
            assert_eq!(value, original, "xor length {length}");

            transform_center_out(&mut value, center_selector, center_seed, 0x6d3a5b17);
            transform_center_out(&mut value, center_selector, center_seed, 0x6d3a5b17);
            assert_eq!(value, original, "center-out length {length}");

            transform_stride(&mut value, stride_selector, stride_seed, 0x2e4981c3);
            transform_stride(&mut value, stride_selector, stride_seed, 0x2e4981c3);
            assert_eq!(value, original, "stride length {length}");
        }
    }
}

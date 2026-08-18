//! Reimplementation of the native v3 client REST envelope.

use std::fmt;
use std::fs::File;
use std::io::Read;

use serde::{Deserialize, Serialize};

use super::{base64_decode, base64_encode, constant_time_eq, ops, prf};

pub const REST_STREAM: &[u8] = b"nuke-client-stream-v3";
pub const REST_KID: &str = "d8e39774";

const REST_CONTEXT: &[u8] = b"d8e39774";
const REST_ROOT_KEY: [u8; 64] = [
    b'c', b'b', b'8', b'4', b'2', b'9', b'9', b'6', b'3', b'7', b'd', b'2', b'c', b'1', b'3', b'f',
    b'a', b'5', b'2', b'7', b'6', b'6', b'9', b'7', b'0', b'f', b'6', b'3', b'4', b'c', b'8', b'c',
    b'8', b'0', b'a', b'7', b'5', b'0', b'd', b'c', b'9', b'c', b'8', b'3', b'9', b'5', b'7', b'3',
    b'c', b'7', b'1', b'e', b'a', b'2', b'c', b'd', b'd', b'0', b'2', b'3', b'5', b'1', b'a', b'9',
];
const REST_SECRET: &[u8; 64] = b"141da00fe2e426fce669a0d1736e96f4aa0b0d269e20d331ad2fa25904417699";

const TAG_NMS3: u32 = 0x4e4d_5333;
const TAG_NST3: u32 = 0x4e53_5433;
const TAG_NPX3: u32 = 0x4e50_5833;
const TAG_NXP3: u32 = 0x4e58_5033;
const TAG_NTG3: u32 = 0x4e54_4733;
const TAG_NVM3: u32 = 0x4e56_4d33;
const TAG_NGD3: u32 = 0x4e47_4433;
const TAG_NHS3: u32 = 0x4e48_5333;
const TAG_NPD3: u32 = 0x4e50_4433;
const TAG_NGC3: u32 = 0x4e47_4333;
const TAG_NAT3: u32 = 0x4e41_5433;

const HEADER_MAGIC: &[u8; 4] = b"NSV3";
const HEADER_SENTINEL: u16 = 0xa703;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct EncryptedEnvelope {
    pub v: u8,
    pub iv: String,
    pub kid: String,
    pub payload: String,
    pub tag: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RestCryptoError {
    RandomSource(String),
    InvalidVersion,
    InvalidKid,
    InvalidIv,
    InvalidPayload,
    InvalidTag,
    AuthenticationFailed,
    InvalidHeader,
    InvalidLength,
}

impl fmt::Display for RestCryptoError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::RandomSource(message) => write!(formatter, "random source failed: {message}"),
            Self::InvalidVersion => formatter.write_str("invalid envelope version"),
            Self::InvalidKid => formatter.write_str("invalid envelope key id"),
            Self::InvalidIv => formatter.write_str("invalid envelope iv"),
            Self::InvalidPayload => formatter.write_str("invalid envelope payload"),
            Self::InvalidTag => formatter.write_str("invalid envelope tag"),
            Self::AuthenticationFailed => formatter.write_str("envelope authentication failed"),
            Self::InvalidHeader => formatter.write_str("invalid decrypted header"),
            Self::InvalidLength => formatter.write_str("invalid decrypted length"),
        }
    }
}

impl std::error::Error for RestCryptoError {}

fn key_schedule(stream: &[u8], iv: &[u8; 24]) -> [u8; 192] {
    let seed = prf::prf(
        TAG_NMS3,
        &[&REST_ROOT_KEY, REST_SECRET, REST_CONTEXT, stream],
    );
    let mut output = [0u8; 192];
    let tags = [TAG_NST3, TAG_NPX3, TAG_NXP3, TAG_NTG3, TAG_NVM3, TAG_NGD3];
    for (index, tag) in tags.into_iter().enumerate() {
        let key = prf::prf(tag, &[&seed, iv, stream, REST_SECRET]);
        output[index * 32..(index + 1) * 32].copy_from_slice(&key);
    }
    output
}

fn subkey(keyctx: &[u8; 192], index: usize) -> [u8; 32] {
    keyctx[index * 32..(index + 1) * 32]
        .try_into()
        .expect("fixed key context layout")
}

fn message_seed_and_tail(
    plaintext_len: usize,
    stream: &[u8],
    iv: &[u8; 24],
    sk5: &[u8; 32],
) -> ([u8; 32], Vec<u8>) {
    let plaintext_len_bytes = (plaintext_len as u64).to_le_bytes();
    let seed = prf::prf(TAG_NHS3, &[sk5, iv, stream, &plaintext_len_bytes]);
    let tail_len = (u64::from_le_bytes(seed[..8].try_into().unwrap()) & 0x3f) as usize
        + ((seed[0] as usize + plaintext_len) & 1)
        + 0x20;

    let mut tail = Vec::with_capacity(tail_len);
    let mut counter = 0u64;
    while tail.len() < tail_len {
        let counter_bytes = counter.to_le_bytes();
        let block = prf::prf(TAG_NPD3, &[sk5, iv, stream, &counter_bytes]);
        let take = (tail_len - tail.len()).min(block.len());
        tail.extend_from_slice(&block[..take]);
        counter = counter.wrapping_add(1);
    }
    (seed, tail)
}

fn envelope_mac(ciphertext: &[u8], stream: &[u8], iv: &[u8; 24], sk3: &[u8; 32]) -> [u8; 32] {
    let marker = [3u8];
    let ciphertext_len = (ciphertext.len() as u64).to_le_bytes();
    prf::prf(
        TAG_NAT3,
        &[
            &marker,
            REST_CONTEXT,
            stream,
            iv,
            &ciphertext_len,
            ciphertext,
            sk3,
        ],
    )
}

/// Native-equivalent REST encryption with an explicit IV for oracle testing.
pub fn encrypt_json_bytes_with_iv(
    plaintext: &[u8],
    stream: &[u8],
    iv: &[u8; 24],
) -> EncryptedEnvelope {
    let keyctx = key_schedule(stream, iv);
    let sk0 = subkey(&keyctx, 0);
    let sk1 = subkey(&keyctx, 1);
    let sk2 = subkey(&keyctx, 2);
    let sk3 = subkey(&keyctx, 3);
    let sk4 = subkey(&keyctx, 4);
    let sk5 = subkey(&keyctx, 5);

    let (seed, tail) = message_seed_and_tail(plaintext.len(), stream, iv, &sk5);
    let mut header = [0u8; 32];
    header[..4].copy_from_slice(HEADER_MAGIC);
    header[4..6].copy_from_slice(&HEADER_SENTINEL.to_le_bytes());
    header[6] = 0x20;
    header[7] = tail.len() as u8;
    header[8..16].copy_from_slice(&(plaintext.len() as u64).to_le_bytes());
    header[16..24].copy_from_slice(&seed[..8]);
    let header_tag = prf::prf(TAG_NGC3, &[&sk5, stream, &header[..24], plaintext, &tail]);
    header[24..32].copy_from_slice(&header_tag[..8]);

    let mut ciphertext = Vec::with_capacity(32 + plaintext.len() + tail.len());
    ciphertext.extend_from_slice(&header);
    ciphertext.extend_from_slice(plaintext);
    ciphertext.extend_from_slice(&tail);
    ops::op1_permute(&mut ciphertext[32..], &sk4, iv, stream, false);
    ops::op2_xor(&mut ciphertext, &sk1, iv, stream, false);
    ops::op3_xor(&mut ciphertext, &sk0, iv, stream);
    ops::op4_center_xor(&mut ciphertext, &sk2, iv, stream, false);
    ops::op5_feistel(&mut ciphertext, &sk5, iv, stream, false);

    let tag = envelope_mac(&ciphertext, stream, iv, &sk3);
    EncryptedEnvelope {
        v: 3,
        iv: base64_encode(iv),
        kid: REST_KID.to_owned(),
        payload: base64_encode(&ciphertext),
        tag: base64_encode(&tag),
    }
}

/// Native-equivalent REST encryption using 24 bytes from `/dev/urandom`.
pub fn encrypt_json_bytes(
    plaintext: &[u8],
    stream: &[u8],
) -> Result<EncryptedEnvelope, RestCryptoError> {
    let mut iv = [0u8; 24];
    File::open("/dev/urandom")
        .and_then(|mut source| source.read_exact(&mut iv))
        .map_err(|error| RestCryptoError::RandomSource(error.to_string()))?;
    Ok(encrypt_json_bytes_with_iv(plaintext, stream, &iv))
}

/// Authenticate and decrypt a native v3 REST envelope.
pub fn decrypt_json_bytes(
    envelope: &EncryptedEnvelope,
    stream: &[u8],
) -> Result<Vec<u8>, RestCryptoError> {
    if envelope.v != 3 {
        return Err(RestCryptoError::InvalidVersion);
    }
    if envelope.kid != REST_KID {
        return Err(RestCryptoError::InvalidKid);
    }
    let iv: [u8; 24] = base64_decode(&envelope.iv)
        .ok_or(RestCryptoError::InvalidIv)?
        .try_into()
        .map_err(|_| RestCryptoError::InvalidIv)?;
    let mut ciphertext = base64_decode(&envelope.payload).ok_or(RestCryptoError::InvalidPayload)?;
    if ciphertext.len() <= 31 || ciphertext.len() & 1 != 0 {
        return Err(RestCryptoError::InvalidPayload);
    }
    let supplied_tag = base64_decode(&envelope.tag).ok_or(RestCryptoError::InvalidTag)?;
    if supplied_tag.len() != 32 {
        return Err(RestCryptoError::InvalidTag);
    }

    let keyctx = key_schedule(stream, &iv);
    let sk0 = subkey(&keyctx, 0);
    let sk1 = subkey(&keyctx, 1);
    let sk2 = subkey(&keyctx, 2);
    let sk3 = subkey(&keyctx, 3);
    let sk4 = subkey(&keyctx, 4);
    let sk5 = subkey(&keyctx, 5);
    let expected_tag = envelope_mac(&ciphertext, stream, &iv, &sk3);
    if !constant_time_eq(&supplied_tag, &expected_tag) {
        return Err(RestCryptoError::AuthenticationFailed);
    }

    ops::op5_feistel(&mut ciphertext, &sk5, &iv, stream, true);
    ops::op4_center_xor(&mut ciphertext, &sk2, &iv, stream, true);
    ops::op3_xor(&mut ciphertext, &sk0, &iv, stream);
    ops::op2_xor(&mut ciphertext, &sk1, &iv, stream, true);
    ops::op1_permute(&mut ciphertext[32..], &sk4, &iv, stream, true);

    if &ciphertext[..4] != HEADER_MAGIC
        || ciphertext[4..6] != HEADER_SENTINEL.to_le_bytes()
        || ciphertext[6] != 0x20
    {
        return Err(RestCryptoError::InvalidHeader);
    }
    let tail_len = ciphertext[7] as usize;
    let plaintext_len = usize::try_from(u64::from_le_bytes(ciphertext[8..16].try_into().unwrap()))
        .map_err(|_| RestCryptoError::InvalidLength)?;
    let expected_len = 32usize
        .checked_add(plaintext_len)
        .and_then(|length| length.checked_add(tail_len))
        .ok_or(RestCryptoError::InvalidLength)?;
    if ciphertext.len() != expected_len {
        return Err(RestCryptoError::InvalidLength);
    }

    let plaintext = &ciphertext[32..32 + plaintext_len];
    let tail = &ciphertext[32 + plaintext_len..];
    let expected_header_tag = prf::prf(
        TAG_NGC3,
        &[&sk5, stream, &ciphertext[..24], plaintext, tail],
    );
    if !constant_time_eq(&ciphertext[24..32], &expected_header_tag[..8]) {
        return Err(RestCryptoError::InvalidHeader);
    }
    Ok(plaintext.to_vec())
}

#[cfg(test)]
mod tests {
    use super::{
        EncryptedEnvelope, REST_STREAM, RestCryptoError, decrypt_json_bytes,
        encrypt_json_bytes_with_iv,
    };

    #[test]
    fn rest_envelope_round_trips_boundary_lengths() {
        let iv = [0x11u8; 24];
        for length in [0usize, 1, 31, 32, 63, 64, 65, 100, 255, 1024] {
            let plaintext: Vec<u8> = (0..length)
                .map(|index| (index as u8).wrapping_mul(0x3d).wrapping_add(length as u8))
                .collect();
            let envelope = encrypt_json_bytes_with_iv(&plaintext, REST_STREAM, &iv);
            assert_eq!(
                decrypt_json_bytes(&envelope, REST_STREAM),
                Ok(plaintext),
                "round trip failed at length {length}"
            );
        }
    }

    #[test]
    fn rest_envelope_matches_native_oracle() {
        let iv = [0x11u8; 24];
        let envelope = encrypt_json_bytes_with_iv(b"{\"probe\":true}", REST_STREAM, &iv);
        assert_eq!(envelope.iv, "ERERERERERERERERERERERERERERERER");
        assert_eq!(envelope.kid, "d8e39774");
        assert_eq!(
            envelope.payload,
            "bXLxdVWPrWrYhW4Avf7c7mkyMqJADtyH/W79u+WIXGj3GFOiEW8cWk/Q68ShdelFBKT0eSmZPLaMm9Yu+IZS5tKToct94hXHY2yQN+Z54U38yGHgwEo="
        );
        assert_eq!(envelope.tag, "OlZfMmemHeHmQiIHIpXZ9/+eMfosDmh128/8PiCD6cw=");
        assert_eq!(
            decrypt_json_bytes(&envelope, REST_STREAM),
            Ok(b"{\"probe\":true}".to_vec())
        );
    }

    #[test]
    fn rest_envelope_rejects_tampering() {
        let iv = [0x22u8; 24];
        let mut envelope = encrypt_json_bytes_with_iv(b"{\"probe\":true}", REST_STREAM, &iv);
        let replacement = if envelope.payload.as_bytes()[0] == b'A' {
            'B'
        } else {
            'A'
        };
        envelope
            .payload
            .replace_range(..1, &replacement.to_string());
        assert_eq!(
            decrypt_json_bytes(&envelope, REST_STREAM),
            Err(RestCryptoError::AuthenticationFailed)
        );
    }

    #[test]
    fn rest_envelope_validates_framing() {
        let invalid = EncryptedEnvelope {
            v: 2,
            iv: String::new(),
            kid: String::new(),
            payload: String::new(),
            tag: String::new(),
        };
        assert_eq!(
            decrypt_json_bytes(&invalid, REST_STREAM),
            Err(RestCryptoError::InvalidVersion)
        );
    }
}

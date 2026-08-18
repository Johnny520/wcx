//! Standalone mirror of the Nuke config-AEAD reimplementation (kept in sync with
//! WeKit/app/src/main/rust/wekit-native/src/nuke_crypto/). Host-testable.

pub mod ops;
pub mod perm;
pub mod prf;
pub mod rest;
pub mod sign;
pub mod tests_perm;

pub const KEY_TABLE: [u8; 32] = [
    0x5c, 0xa8, 0xfa, 0xe6, 0xdd, 0x9b, 0x58, 0x5b, 0x87, 0x85, 0x9b, 0x2f, 0x05, 0xe0, 0x30, 0xd0,
    0xea, 0xbb, 0xe7, 0x2c, 0xab, 0xc2, 0xbd, 0x16, 0xa6, 0x71, 0x2e, 0x97, 0x52, 0x0f, 0xb6, 0xcd,
];

pub const CONFIG_ROOT_KEY_HEX: &str =
    "23fb18aec69d69c1b7cd66368c56e2f26c5ab7356ae88ebc983945e604b82316";

pub const TAG_NCM4: u32 = 0x4e434d34;
pub const TAG_NCS4: u32 = 0x4e435334;
pub const TAG_NCP4: u32 = 0x4e435034;
pub const TAG_NCX4: u32 = 0x4e435834;
pub const TAG_NCT4: u32 = 0x4e435434;
pub const TAG_NCV4: u32 = 0x4e435634;
pub const TAG_NCG4: u32 = 0x4e434734;
pub const TAG_NHS3: u32 = 0x4e485333;
pub const TAG_NPD3: u32 = 0x4e504433;
pub const TAG_NGC3: u32 = 0x4e474333;
pub const TAG_NCA4: u32 = 0x4e434134;

pub const INFO_STABLE: &[u8] = b"nuke-conf-stable-v1";
pub const INFO_VALUE: &[u8] = b"nuke-conf-value";
const VERSION_MAC: &[u8] = b"c3";
const MAGIC_NCF3: &[u8; 4] = b"NCF3";

/// Build the six 32-byte subkeys consumed by `FUN_00ea659c` for config values.
/// The native caller passes the 64-byte ASCII root key material (the hex text,
/// not its binary decoding) through NCM4, then domain-separates each slot with
/// the same nonce and `nuke-conf-value` context.
pub fn key_schedule(nonce: &[u8; 24]) -> [u8; 192] {
    let root = CONFIG_ROOT_KEY_HEX.as_bytes();
    let seed = prf::prf(TAG_NCM4, &[root, INFO_STABLE, INFO_VALUE]);
    let mut out = [0u8; 192];
    let tags = [TAG_NCS4, TAG_NCP4, TAG_NCX4, TAG_NCT4, TAG_NCV4, TAG_NCG4];
    for (index, tag) in tags.into_iter().enumerate() {
        let subkey = prf::prf(tag, &[&seed, nonce, INFO_VALUE]);
        out[index * 32..(index + 1) * 32].copy_from_slice(&subkey);
    }
    out
}

fn subkey(keyctx: &[u8; 192], index: usize) -> [u8; 32] {
    keyctx[index * 32..(index + 1) * 32].try_into().unwrap()
}

fn message_seed_and_tail(
    plaintext_len: usize,
    nonce: &[u8; 24],
    sk5: &[u8; 32],
) -> ([u8; 32], Vec<u8>) {
    let plaintext_len_bytes = (plaintext_len as u64).to_le_bytes();
    let seed = prf::prf(TAG_NHS3, &[sk5, nonce, INFO_VALUE, &plaintext_len_bytes]);
    let tail_len = (u64::from_le_bytes(seed[..8].try_into().unwrap()) & 0x3f) as usize
        + ((seed[0] as usize + plaintext_len) & 1)
        + 0x20;

    let mut tail = Vec::with_capacity(tail_len);
    let mut counter = 0u64;
    while tail.len() < tail_len {
        let counter_bytes = counter.to_le_bytes();
        let block = prf::prf(TAG_NPD3, &[sk5, nonce, INFO_VALUE, &counter_bytes]);
        let take = (tail_len - tail.len()).min(block.len());
        tail.extend_from_slice(&block[..take]);
        counter = counter.wrapping_add(1);
    }
    (seed, tail)
}

fn config_mac(ciphertext: &[u8], nonce: &[u8; 24], sk3: &[u8; 32]) -> [u8; 32] {
    let marker = [3u8];
    let ciphertext_len = (ciphertext.len() as u64).to_le_bytes();
    prf::prf(
        TAG_NCA4,
        &[
            &marker,
            VERSION_MAC,
            INFO_VALUE,
            nonce,
            &ciphertext_len,
            ciphertext,
            sk3,
        ],
    )
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right)
        .fold(0u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let a = chunk[0];
        let b = chunk.get(1).copied().unwrap_or(0);
        let c = chunk.get(2).copied().unwrap_or(0);
        output.push(TABLE[(a >> 2) as usize] as char);
        output.push(TABLE[((a & 0x03) << 4 | b >> 4) as usize] as char);
        if chunk.len() > 1 {
            output.push(TABLE[((b & 0x0f) << 2 | c >> 6) as usize] as char);
        } else {
            output.push('=');
        }
        if chunk.len() > 2 {
            output.push(TABLE[(c & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
    }
    output
}

fn base64_value(byte: u8) -> Option<u8> {
    match byte {
        b'A'..=b'Z' => Some(byte - b'A'),
        b'a'..=b'z' => Some(byte - b'a' + 26),
        b'0'..=b'9' => Some(byte - b'0' + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

fn base64_decode(input: &str) -> Option<Vec<u8>> {
    let bytes = input.as_bytes();
    if bytes.len() % 4 != 0 {
        return None;
    }
    let mut output = Vec::with_capacity(bytes.len() / 4 * 3);
    let chunk_count = bytes.len() / 4;
    for (index, chunk) in bytes.chunks_exact(4).enumerate() {
        let last = index + 1 == chunk_count;
        let pad2 = chunk[2] == b'=';
        let pad3 = chunk[3] == b'=';
        if !last && (pad2 || pad3) || pad2 && !pad3 {
            return None;
        }
        let a = base64_value(chunk[0])?;
        let b = base64_value(chunk[1])?;
        let c = if pad2 { 0 } else { base64_value(chunk[2])? };
        let d = if pad3 { 0 } else { base64_value(chunk[3])? };
        if pad2 && b & 0x0f != 0 || pad3 && !pad2 && c & 0x03 != 0 {
            return None;
        }
        output.push(a << 2 | b >> 4);
        if !pad2 {
            output.push(b << 4 | c >> 2);
        }
        if !pad3 {
            output.push(c << 6 | d);
        }
    }
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::{config_decrypt, config_encrypt, key_schedule};

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn key_schedule_matches_phase2_keyctx() {
        let nonce = [0x11u8; 24];
        let got = key_schedule(&nonce);
        let expected: [u8; 192] = hex(
            "e8675f91dddf9c5ee00d4462d40dee23a0594dae2e3a6c497f91b34cb98cd465\
             bd25b7c746332d318644f9dd4211c6e0bf981b251d6ffb04c10cabc50852cd49\
             de2e8b5b8c428dba96f59fee961ae55eb92ec9146ef5d46ee3265c2c4d4a5ba9\
             18618aed44246144e96b4ca9e0807a7eea0db48e45d0376ac3ff39aaad35b341\
             66fffe488f4687dc32cfa23685790efcf3cc259d7aa7d2c4dddced25659f92ba\
             9cfbb2ba9b27ba1f2c28ba7df956c2482b8c6bbae4347b630043265a1169a01a",
        )
        .try_into()
        .unwrap();
        assert_eq!(got, expected);
    }

    #[test]
    fn config_envelope_matches_phase2_oracle() {
        let nonce = [0x11u8; 24];
        let plaintext = b"test_config_value";
        let expected = "C3:ERERERERERERERERERERERERERERERER:\
            wWXmC7uoYoUUuHCutIdUnDro9TPDMkrrZBMzoiK5KuhOj5YaewI8hvh6m2mCmxGF\
            ienTToBvz7aC8bUr9IIGzAxkb8+U1DASPpU5vEWQHUhK8n8fXkOpFFSZ2Ac7cDah\
            5RjmSg==:u87xR0VGrcMkr/9XprkZLY+n6Inn22q+32ETiAJwj+I=";
        let envelope = config_encrypt(plaintext, &nonce);
        assert_eq!(envelope, expected);
        assert_eq!(config_decrypt(&envelope), Some(plaintext.to_vec()));

        let mut damaged = envelope.into_bytes();
        let last = damaged.len() - 2;
        damaged[last] = if damaged[last] == b'A' { b'B' } else { b'A' };
        assert_eq!(config_decrypt(std::str::from_utf8(&damaged).unwrap()), None);
    }

    #[test]
    fn config_envelope_matches_second_native_oracle() {
        let nonce = [0x11u8; 24];
        let plaintext = b"second_probe_value_XYZ";
        let expected = "C3:ERERERERERERERERERERERERERERERER:\
            sEfgS3m+9j1XpcjtaEV6u5zl+KMihgy+7VU6jTnGP5yMjBkFaNG9C9OWYpmg56E9\
            Z8reUDzds1ANjLdf88EYELH56qLJOOsCuuZ9R2jntiPxpFdDSY0X5k/WbCwwCp8\
            pAO36vMSg3XAQAGVorFZ3aQ==:ItsttRDxDtpbNgtrojSWB9WOq9vJ00JvHWFHnbvqUAs=";

        let envelope = config_encrypt(plaintext, &nonce);
        assert_eq!(envelope, expected);
        assert_eq!(config_decrypt(&envelope), Some(plaintext.to_vec()));
    }

    #[test]
    fn config_envelope_matches_different_nonce_oracle() {
        let nonce = [0x22u8; 24];
        let plaintext = b"test_config_value";
        let expected = "C3:IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIi:\
            QPAp/sbD1Z5WO5V5GWTb3zYMYWnUQzHV9sP9oeEK3dYwxZcyCjJOcTlncKnx/gMq\
            Yr5sE21cyoL1QD4unPqhWtv4oXveNQ9c/TR3d8oYm4L7FhphBdWUoHY1bufCifr\
            OM3vSz5yfia6wWNx30grwsTOm5GU=:Nb99eTzBv45lKc7MoFEr3KCRujjVBt5z2tcYJDjMd8I=";

        let envelope = config_encrypt(plaintext, &nonce);
        assert_eq!(envelope, expected);
        assert_eq!(config_decrypt(&envelope), Some(plaintext.to_vec()));
    }

    #[test]
    fn config_round_trips_boundary_lengths() {
        let nonce = [0xa5u8; 24];
        for length in [0usize, 1, 31, 32, 63, 64, 65, 100, 255] {
            let plaintext: Vec<u8> = (0..length)
                .map(|index| (index as u8).wrapping_mul(0x3d).wrapping_add(length as u8))
                .collect();
            let envelope = config_encrypt(&plaintext, &nonce);
            assert_eq!(
                config_decrypt(&envelope),
                Some(plaintext),
                "round trip failed for plaintext length {length}"
            );
        }
    }
}

/// Encrypt a config value using the recovered native C3 pipeline.
///
/// The nonce is explicit so captured native vectors can be reproduced exactly.
/// The JNI wrapper generates this 24-byte value with `getrandom` before calling
/// the native envelope builder; callers that need native-style randomness must
/// supply a fresh cryptographically secure nonce here.
///
/// Returns `C3:b64(nonce):b64(ciphertext):b64(tag)`.
pub fn config_encrypt(plaintext: &[u8], nonce: &[u8; 24]) -> String {
    let keyctx = key_schedule(nonce);
    let sk0 = subkey(&keyctx, 0);
    let sk1 = subkey(&keyctx, 1);
    let sk2 = subkey(&keyctx, 2);
    let sk3 = subkey(&keyctx, 3);
    let sk4 = subkey(&keyctx, 4);
    let sk5 = subkey(&keyctx, 5);

    let (seed, tail) = message_seed_and_tail(plaintext.len(), nonce, &sk5);
    let mut header = [0u8; 32];
    header[..4].copy_from_slice(MAGIC_NCF3);
    header[4..6].copy_from_slice(&0xa703u16.to_le_bytes());
    header[6] = 0x20;
    header[7] = tail.len() as u8;
    header[8..16].copy_from_slice(&(plaintext.len() as u64).to_le_bytes());
    header[16..24].copy_from_slice(&seed[..8]);
    let header_tag = prf::prf(
        TAG_NGC3,
        &[&sk5, INFO_VALUE, &header[..24], plaintext, &tail],
    );
    header[24..32].copy_from_slice(&header_tag[..8]);

    let mut ciphertext = Vec::with_capacity(32 + plaintext.len() + tail.len());
    ciphertext.extend_from_slice(&header);
    ciphertext.extend_from_slice(plaintext);
    ciphertext.extend_from_slice(&tail);
    ops::op1_permute(&mut ciphertext[32..], &sk4, nonce, INFO_VALUE, false);
    ops::op2_xor(&mut ciphertext, &sk1, nonce, INFO_VALUE, false);
    ops::op3_xor(&mut ciphertext, &sk0, nonce, INFO_VALUE);
    ops::op4_center_xor(&mut ciphertext, &sk2, nonce, INFO_VALUE, false);
    ops::op5_feistel(&mut ciphertext, &sk5, nonce, INFO_VALUE, false);

    let tag = config_mac(&ciphertext, nonce, &sk3);
    format!(
        "C3:{}:{}:{}",
        base64_encode(nonce),
        base64_encode(&ciphertext),
        base64_encode(&tag)
    )
}

/// Decrypt and authenticate a `C3:...` config envelope.
///
/// Returns `None` for malformed Base64/framing, a wrong NCA4 MAC, an invalid
/// NCF3 header, a mismatched NPD3 tail, or a failed NGC3 header check.
pub fn config_decrypt(envelope: &str) -> Option<Vec<u8>> {
    let mut fields = envelope.split(':');
    if fields.next()? != "C3" {
        return None;
    }
    let nonce: [u8; 24] = base64_decode(fields.next()?)?.try_into().ok()?;
    let mut ciphertext = base64_decode(fields.next()?)?;
    let supplied_tag = base64_decode(fields.next()?)?;
    if fields.next().is_some() || supplied_tag.len() != 32 {
        return None;
    }

    let keyctx = key_schedule(&nonce);
    let sk0 = subkey(&keyctx, 0);
    let sk1 = subkey(&keyctx, 1);
    let sk2 = subkey(&keyctx, 2);
    let sk3 = subkey(&keyctx, 3);
    let sk4 = subkey(&keyctx, 4);
    let sk5 = subkey(&keyctx, 5);
    let expected_tag = config_mac(&ciphertext, &nonce, &sk3);
    if !constant_time_eq(&supplied_tag, &expected_tag) {
        return None;
    }

    ops::op5_feistel(&mut ciphertext, &sk5, &nonce, INFO_VALUE, true);
    ops::op4_center_xor(&mut ciphertext, &sk2, &nonce, INFO_VALUE, true);
    ops::op3_xor(&mut ciphertext, &sk0, &nonce, INFO_VALUE);
    ops::op2_xor(&mut ciphertext, &sk1, &nonce, INFO_VALUE, true);
    if ciphertext.len() < 32 {
        return None;
    }
    ops::op1_permute(&mut ciphertext[32..], &sk4, &nonce, INFO_VALUE, true);

    if &ciphertext[..4] != MAGIC_NCF3
        || ciphertext[4..6] != 0xa703u16.to_le_bytes()
        || ciphertext[6] != 0x20
    {
        return None;
    }
    let tail_len = ciphertext[7] as usize;
    let plaintext_len =
        usize::try_from(u64::from_le_bytes(ciphertext[8..16].try_into().unwrap())).ok()?;
    let expected_len = 32usize.checked_add(plaintext_len)?.checked_add(tail_len)?;
    if ciphertext.len() != expected_len {
        return None;
    }

    let plaintext = ciphertext[32..32 + plaintext_len].to_vec();
    let tail = &ciphertext[32 + plaintext_len..];
    let (expected_seed, expected_tail) = message_seed_and_tail(plaintext_len, &nonce, &sk5);
    if ciphertext[16..24] != expected_seed[..8] || !constant_time_eq(tail, &expected_tail) {
        return None;
    }
    let expected_header_tag = prf::prf(
        TAG_NGC3,
        &[&sk5, INFO_VALUE, &ciphertext[..24], &plaintext, tail],
    );
    if ciphertext[24..32] != expected_header_tag[..8] {
        return None;
    }
    Some(plaintext)
}

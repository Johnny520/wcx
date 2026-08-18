//! Recovered buffer transforms called after the large per-op proof wrappers.

use super::prf::prf;

const TAG_NPM3: u32 = 0x4e50_4d33;
const TAG_NPR3: u32 = 0x4e50_5233;
const TAG_NBL3: u32 = 0x4e42_4c33;
const TAG_OP3_SEED: u32 = 0xebe7_1669;
const TAG_NPO3: u32 = 0x4e50_4f33;
const TAG_NFS3: u32 = 0x4e46_5333;

const AES_SBOX: [u8; 256] = [
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

/// op1 tail (`FUN_00ea3140`): PRF-driven Fisher-Yates permutation.
pub fn op1_permute(buf: &mut [u8], key: &[u8; 32], nonce: &[u8; 24], info: &[u8], reverse: bool) {
    if buf.len() < 2 {
        return;
    }

    let mut swaps = Vec::with_capacity(buf.len() - 1);
    let mut prf_block = [0u8; 32];
    let mut prf_pos = 32usize;
    let mut counter = 0u64;

    for remaining in (2..=buf.len()).rev() {
        let mut random_bytes = [0u8; 8];
        for byte in &mut random_bytes {
            if prf_pos >= prf_block.len() {
                let counter_bytes = counter.to_le_bytes();
                prf_block = prf(TAG_NPM3, &[key, nonce, info, &counter_bytes]);
                prf_pos = 0;
                counter = counter.wrapping_add(1);
            }
            *byte = prf_block[prf_pos];
            prf_pos += 1;
        }
        let random = u64::from_le_bytes(random_bytes);
        swaps.push((remaining - 1, (random % remaining as u64) as usize));
    }

    if reverse {
        for &(a, b) in swaps.iter().rev() {
            buf.swap(a, b);
        }
    } else {
        for (a, b) in swaps {
            buf.swap(a, b);
        }
    }
}

/// op2 tail (`FUN_00e97f74`): chained byte-wise XOR with an NPR3 stream.
/// The first stream byte is retained as a carry; bytes 1..31 are consumed for
/// the first 31 positions, then subsequent 32-byte blocks are consumed from
/// offset zero with an incrementing little-endian counter slice.
pub fn op2_xor(buf: &mut [u8], key: &[u8; 32], nonce: &[u8; 24], info: &[u8], reverse: bool) {
    if buf.is_empty() {
        return;
    }

    let mut counter = 0u64;
    let mut counter_bytes = counter.to_le_bytes();
    let mut block = prf(TAG_NPR3, &[key, nonce, info, &counter_bytes]);
    let mut carry = block[0];
    let mut pos = 1usize;
    counter = 1;

    for (index, byte) in buf.iter_mut().enumerate() {
        let original = *byte;
        if pos > 31 {
            counter_bytes = counter.to_le_bytes();
            block = prf(TAG_NPR3, &[key, nonce, info, &counter_bytes]);
            counter = counter.wrapping_add(1);
            pos = 0;
        }
        let stream = block[pos];
        pos += 1;
        let mixed =
            carry.rotate_left(1) ^ (index as u32).wrapping_mul(0x3d) as u8 ^ stream ^ original;
        *byte = mixed;
        carry = if reverse { original } else { mixed };
    }
}

/// op3 tail (`FUN_00e9d728`): XOR with 32-byte NBL3 blocks keyed by an
/// operation-specific seed PRF. This operation is self-inverse.
pub fn op3_xor(buf: &mut [u8], key: &[u8; 32], nonce: &[u8; 24], info: &[u8]) {
    let mut chain = prf(TAG_OP3_SEED, &[key, nonce, info]);
    for (counter, chunk) in buf.chunks_mut(32).enumerate() {
        let counter_bytes = (counter as u64).to_le_bytes();
        let block = prf(TAG_NBL3, &[key, nonce, info, &chain, &counter_bytes]);
        for (byte, stream) in chunk.iter_mut().zip(block) {
            *byte ^= stream;
        }
        chain = block;
    }
}

/// op4 tail (`FUN_00ea6bfc`): process bytes from the center outward with a
/// chained NPO3 stream. Forward mode chains transformed bytes; reverse mode
/// chains the incoming ciphertext bytes.
pub fn op4_center_xor(
    buf: &mut [u8],
    key: &[u8; 32],
    nonce: &[u8; 24],
    info: &[u8],
    reverse: bool,
) {
    if buf.is_empty() {
        return;
    }

    let left = (buf.len() - 1) / 2;
    let right = buf.len() / 2;
    let mut order = Vec::with_capacity(buf.len());
    order.push(left);
    if right != left {
        order.push(right);
    }
    for distance in 1..=left.max(buf.len() - 1 - right) {
        if distance <= left {
            order.push(left - distance);
        }
        if right + distance < buf.len() {
            order.push(right + distance);
        }
    }

    let mut counter = 0u64;
    let mut counter_bytes = counter.to_le_bytes();
    let mut block = prf(TAG_NPO3, &[key, nonce, info, &counter_bytes]);
    let mut carry = block[0];
    let mut pos = 1usize;
    counter = 1;

    for index in order {
        if pos > 31 {
            counter_bytes = counter.to_le_bytes();
            block = prf(TAG_NPO3, &[key, nonce, info, &counter_bytes]);
            counter = counter.wrapping_add(1);
            pos = 0;
        }
        let original = buf[index];
        let mixed = (index as u32).wrapping_mul(0x11) as u8 ^ block[pos] ^ carry ^ original;
        pos += 1;
        buf[index] = mixed;
        let chained = if reverse { original } else { mixed };
        carry = chained ^ carry.rotate_left(1);
    }
}

#[inline]
fn rol8(value: u8, amount: u8) -> u8 {
    value.rotate_left((amount & 7) as u32)
}

#[inline]
fn ror8(value: u8, amount: u8) -> u8 {
    value.rotate_right((amount & 7) as u32)
}

fn op5_round_byte(digest: &[u8; 32], round: u8, position: usize, input: u8) -> u8 {
    let b3 = digest[(position + round as usize) & 0x1f];
    let b4 = digest[(position * 7 + 0x0b) & 0x1f];
    let index = (position as u8).wrapping_mul(0x3d)
        ^ rol8(input, (round ^ position as u8) & 7)
        ^ b3
        ^ ror8(b4, position as u8 & 7);
    rol8(b3, round & 7) ^ AES_SBOX[index as usize]
}

/// op5 tail (`FUN_00e9ed5c`): six-round Feistel transform over two halves.
pub fn op5_feistel(buf: &mut [u8], key: &[u8; 32], nonce: &[u8; 24], info: &[u8], reverse: bool) {
    if buf.len() <= 1 {
        return;
    }
    let half_len = buf.len() / 2;
    if reverse {
        for round in (0..=5u8).rev() {
            for position in 0..half_len {
                buf.swap(position, half_len + position);
            }
            let half_len_bytes = (half_len as u64).to_le_bytes();
            for block_index in 0..half_len.div_ceil(32) {
                let round_byte = [round];
                let block_bytes = (block_index as u64).to_le_bytes();
                let digest = prf(
                    TAG_NFS3,
                    &[key, nonce, info, &round_byte, &half_len_bytes, &block_bytes],
                );
                let start = block_index * 32;
                let end = (start + 32).min(half_len);
                for position in start..end {
                    let right = buf[half_len + position];
                    buf[position] ^= op5_round_byte(&digest, round, position, right);
                }
            }
        }
    } else {
        for round in 0..=5u8 {
            let half_len_bytes = (half_len as u64).to_le_bytes();
            for block_index in 0..half_len.div_ceil(32) {
                let round_byte = [round];
                let block_bytes = (block_index as u64).to_le_bytes();
                let digest = prf(
                    TAG_NFS3,
                    &[key, nonce, info, &round_byte, &half_len_bytes, &block_bytes],
                );
                let start = block_index * 32;
                let end = (start + 32).min(half_len);
                for position in start..end {
                    let right = buf[half_len + position];
                    buf[position] ^= op5_round_byte(&digest, round, position, right);
                }
            }
            for position in 0..half_len {
                buf.swap(position, half_len + position);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::TAG_NFS3;
    use super::op1_permute;
    use super::op2_xor;
    use super::op3_xor;
    use super::op4_center_xor;
    use super::op5_feistel;
    use super::prf;
    use super::{TAG_NBL3, TAG_OP3_SEED};

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn op1_matches_phase2_oracle() {
        let key: [u8; 32] = hex("66fffe488f4687dc32cfa23685790efcf3cc259d7aa7d2c4dddced25659f92ba")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 24];
        let mut buf = hex(
            "746573745f636f6e6669675f76616c7565d7fa7ee0ad5523446bd9a990375c6a8d0d637cad0b2717f94fdd403991cbed86b048f0130ccb721e3b5d5ba36a87affdf91fc1",
        );
        let original = buf.clone();
        let expected = hex(
            "905ccb6ac18774b0f9e07c7376a3d765aff0396775a9443bdd4f8dad656b86136e915f5b637ef96f173774ad40d9cb0b2369fd275d6c665f0d726a61fa0c1f1eed635548",
        );

        op1_permute(&mut buf, &key, &nonce, b"nuke-conf-value", false);
        assert_eq!(buf, expected);
        op1_permute(&mut buf, &key, &nonce, b"nuke-conf-value", true);
        assert_eq!(buf, original);
    }

    #[test]
    fn op2_matches_phase2_oracle() {
        let key: [u8; 32] = hex("bd25b7c746332d318644f9dd4211c6e0bf981b251d6ffb04c10cabc50852cd49")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 24];
        let mut buf = hex(
            "4e43463303a720331100000000000000d31a52f2d2e433c4770daf104169aab8905ccb6ac18774b0f9e07c7376a3d765aff0396775a9443bdd4f8dad656b86136e915f5b637ef96f173774ad40d9cb0b2369fd275d6c665f0d726a61fa0c1f1eed635548",
        );
        let original = buf.clone();
        let expected = hex(
            "1780908728539f478cae93d104ddd75df6fa3695a266eb6d0d2ab79485fd3109cd76a7997c90aa454d71e3b7229d96799c69c5b99562ee35a8e3c99d62808120e010e208f1192ac9fe72129e36d0485eda29d92611f64179f73b197bc5115c84bf50461b",
        );

        op2_xor(&mut buf, &key, &nonce, b"nuke-conf-value", false);
        assert_eq!(buf, expected);
        op2_xor(&mut buf, &key, &nonce, b"nuke-conf-value", true);
        assert_eq!(buf, original);
    }

    #[test]
    fn op3_matches_phase2_oracle() {
        let key: [u8; 32] = hex("e8675f91dddf9c5ee00d4462d40dee23a0594dae2e3a6c497f91b34cb98cd465")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 24];
        let mut buf = hex(
            "1780908728539f478cae93d104ddd75df6fa3695a266eb6d0d2ab79485fd3109cd76a7997c90aa454d71e3b7229d96799c69c5b99562ee35a8e3c99d62808120e010e208f1192ac9fe72129e36d0485eda29d92611f64179f73b197bc5115c84bf50461b",
        );
        let original = buf.clone();
        let expected = hex(
            "ccef7518048e6440e0b97eacbd3f72260ddb23e01d91a65ed0331292ad40e5e6d48e00707493b9edc91ce6265594400f460664ef0bf3a16968e334fc681cb8faa07d9f405107b67d68b5d754371aa9687d74f5106464476c49f21cb8ebda65291011992f",
        );

        let seed = prf(TAG_OP3_SEED, &[&key, &nonce, b"nuke-conf-value"]);
        let expected_seed: [u8; 32] =
            hex("a9a4c835f6dfbcd17992011b4c6614fda1a3b29ba9684a520cf35c24941b0921")
                .try_into()
                .unwrap();
        assert_eq!(seed, expected_seed);
        let counter_zero = 0u64.to_le_bytes();
        let block_zero = prf(
            TAG_NBL3,
            &[&key, &nonce, b"nuke-conf-value", &seed, &counter_zero],
        );
        let expected_block_zero: [u8; 32] =
            hex("db6fe59f2cddfb076c17ed7db9e2a57bfb211575bff74d33dd19a50628bdd4ef")
                .try_into()
                .unwrap();
        assert_eq!(block_zero, expected_block_zero);
        let counter_one = 1u64.to_le_bytes();
        let block_one = prf(
            TAG_NBL3,
            &[&key, &nonce, b"nuke-conf-value", &block_zero, &counter_one],
        );
        let expected_block_one: [u8; 32] =
            hex("19f8a7e9080313a8846d05917709d676da6fa1569e914f5cc000fd610a9c39da")
                .try_into()
                .unwrap();
        assert_eq!(block_one, expected_block_one);

        op3_xor(&mut buf, &key, &nonce, b"nuke-conf-value");
        assert_eq!(buf, expected);
        op3_xor(&mut buf, &key, &nonce, b"nuke-conf-value");
        assert_eq!(buf, original);
    }

    #[test]
    fn op4_matches_phase2_oracle() {
        let key: [u8; 32] = hex("de2e8b5b8c428dba96f59fee961ae55eb92ec9146ef5d46ee3265c2c4d4a5ba9")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 24];
        let mut buf = hex(
            "ccef7518048e6440e0b97eacbd3f72260ddb23e01d91a65ed0331292ad40e5e6d48e00707493b9edc91ce6265594400f460664ef0bf3a16968e334fc681cb8faa07d9f405107b67d68b5d754371aa9687d74f5106464476c49f21cb8ebda65291011992f",
        );
        let original = buf.clone();
        let expected = hex(
            "9dc0d8e1aef7779c6224f87588343f50ebea6dfccfe059b840aa7b5cb02bf34fe6cf01ab3f0cc5639794966023b7ada3771db1aa162cfe4370a92011cc91c265ea83023204e3b6268ce00e6f6b53ec3fa4418ba3b309d1e6acca723228fc4ba25b30c9e1",
        );

        op4_center_xor(&mut buf, &key, &nonce, b"nuke-conf-value", false);
        assert_eq!(buf, expected);
        op4_center_xor(&mut buf, &key, &nonce, b"nuke-conf-value", true);
        assert_eq!(buf, original);
    }

    #[test]
    fn op5_matches_phase2_oracle() {
        let key: [u8; 32] = hex("9cfbb2ba9b27ba1f2c28ba7df956c2482b8c6bbae4347b630043265a1169a01a")
            .try_into()
            .unwrap();
        let nonce = [0x11u8; 24];
        let mut buf = hex(
            "9dc0d8e1aef7779c6224f87588343f50ebea6dfccfe059b840aa7b5cb02bf34fe6cf01ab3f0cc5639794966023b7ada3771db1aa162cfe4370a92011cc91c265ea83023204e3b6268ce00e6f6b53ec3fa4418ba3b309d1e6acca723228fc4ba25b30c9e1",
        );
        let original = buf.clone();
        let expected = hex(
            "c165e60bbba8628514b870aeb487549c3ae8f533c3324aeb641333a222b92ae84e8f961a7b023c86f87a9b69829b118589e9d34e806fcfb682f1b52bf48206cc0c646fcf94d430123e9539bc45901d484af27f1f5e43a9145499d8073b7036a1e518e64a",
        );
        let round_zero = [0u8];
        let half_len = (buf.len() as u64 / 2).to_le_bytes();
        let block_zero = 0u64.to_le_bytes();
        let first_digest = super::prf(
            TAG_NFS3,
            &[
                &key,
                &nonce,
                b"nuke-conf-value",
                &round_zero,
                &half_len,
                &block_zero,
            ],
        );
        let expected_first_digest: [u8; 32] =
            hex("75089d6300ddc499031cac4dc6896a23e16729321f33abf8185fb1d7f77e34c9")
                .try_into()
                .unwrap();
        assert_eq!(first_digest, expected_first_digest);

        op5_feistel(&mut buf, &key, &nonce, b"nuke-conf-value", false);
        assert_eq!(buf, expected);
        op5_feistel(&mut buf, &key, &nonce, b"nuke-conf-value", true);
        assert_eq!(buf, original);
    }

    #[test]
    fn npm3_matches_native_digest() {
        let key: [u8; 32] = hex("7c74e45ba8b4b2e90685e8ec7599b420b594f1b54caa2c6455bdd045bc5b7933")
            .try_into()
            .unwrap();
        let nonce = [0x42u8; 24];
        let ctr = 0u64.to_le_bytes();
        let slices: &[&[u8]] = &[&key, &nonce, b"self-test", &ctr];
        let got = prf(0x4e50_4d33, slices);
        let expected: [u8; 32] =
            hex("30d7cb506499ee0e4fc970524ae56faa8e99b68d61ca08a6931acaba4e693ac6")
                .try_into()
                .unwrap();
        assert_eq!(got, expected);
    }

    #[test]
    fn all_operations_round_trip_boundary_lengths() {
        let key = [0x6du8; 32];
        let nonce = [0xa5u8; 24];
        for length in [0usize, 1, 2, 31, 32, 33, 63, 64, 65, 100, 101, 255] {
            let original: Vec<u8> = (0..length)
                .map(|index| (index as u8).wrapping_mul(0x3d).wrapping_add(length as u8))
                .collect();

            let mut op1 = original.clone();
            op1_permute(&mut op1, &key, &nonce, b"boundary-test", false);
            op1_permute(&mut op1, &key, &nonce, b"boundary-test", true);
            assert_eq!(op1, original, "op1 length {length}");

            let mut op2 = original.clone();
            op2_xor(&mut op2, &key, &nonce, b"boundary-test", false);
            op2_xor(&mut op2, &key, &nonce, b"boundary-test", true);
            assert_eq!(op2, original, "op2 length {length}");

            let mut op3 = original.clone();
            op3_xor(&mut op3, &key, &nonce, b"boundary-test");
            op3_xor(&mut op3, &key, &nonce, b"boundary-test");
            assert_eq!(op3, original, "op3 length {length}");

            let mut op4 = original.clone();
            op4_center_xor(&mut op4, &key, &nonce, b"boundary-test", false);
            op4_center_xor(&mut op4, &key, &nonce, b"boundary-test", true);
            assert_eq!(op4, original, "op4 length {length}");

            let mut op5 = original.clone();
            op5_feistel(&mut op5, &key, &nonce, b"boundary-test", false);
            op5_feistel(&mut op5, &key, &nonce, b"boundary-test", true);
            assert_eq!(op5, original, "op5 length {length}");
        }
    }
}

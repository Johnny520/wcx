//! The Nuke custom sponge PRF driver (`FUN_00ea6f30`).
//!
//! Structure (all recovered exactly from decompile, except the absorb
//! data-injection step which is still being pinned down):
//!   1. `seed_state(tag)`     — 20×u32 state from the domain tag + pi/IV consts.
//!   2. absorb loop           — per input slice: parse/verify, counter-tweak
//!                              word[19] with the "PMVN" (0x4e564d50) constant,
//!                              then `permute(&mut s, 1)`.
//!   3. `squeeze(&s)`         — 8×u32 splitmix/murmur finalizer → 32 bytes.
//!
//! The core ARX permutation ([`super::perm::permute`]) is bit-exact validated
//! against a captured hardware vector. Seed and squeeze below are transcribed
//! verbatim from `FUN_00ea6f30`.

#![allow(dead_code)]

use super::perm::{permute, ror};

/// IV table `DAT_00921910` reinterpreted as u32 LE words (same table the
/// permutation uses as round constants). Words [1..3],[5..7],[9..11],[13..15]
/// seed fixed state lanes.
pub const IV: [u32; 16] = [
    0x243f6a88, 0x85a308d3, 0x13198a2e, 0x03707344, // 0..3
    0xa4093822, 0x299f31d0, 0x082efa98, 0xec4e6c89, // 4..7
    0x452821e6, 0x38d01377, 0xbe5466cf, 0x34e90c6c, // 8..11
    0xc0ac29b7, 0xc97c50dd, 0x3f84d5b5, 0xb5470917, // 12..15
];

/// Seed the 20-word state from the 32-bit domain tag (`FUN_00ea6f30` prologue).
pub fn seed_state(tag: u32) -> [u32; 20] {
    let mut s = [0u32; 20];
    s[0] = tag ^ 0x243f6a88; // local_298
    s[1] = 0x85a308d3; // local_294 lo of 0x13198a2e85a308d3
    s[2] = 0x13198a2e; // local_294 hi
    s[3] = ror(tag, 23).wrapping_add(0x03707344); // local_28c: (tag>>23|tag<<9)+..
    s[4] = 0xa4093822; // local_288 = DAT_00921920 (bytes 22 38 09 a4)
    s[5] = 0x299f31d0; // local_288+4
    s[6] = 0x082efa98; // local_280
    s[7] = tag.wrapping_mul(0x7feb352d) ^ 0xec4e6c89; // local_27c
    s[8] = 0x452821e6; // local_278 = DAT_00921930 (bytes e6 21 28 45)
    s[9] = 0x38d01377; // local_278+4
    s[10] = 0xbe5466cf; // local_270
    s[11] = tag.wrapping_mul(0xd192ed03).wrapping_add(0x34e90c6c); // local_26c: tag*-0x2e6d12fd+..
    s[12] = 0xc0ac29b7; // local_268 = DAT_00921940 (bytes b7 29 ac c0)
    s[13] = 0xc97c50dd; // local_268+4
    s[14] = 0x3f84d5b5; // local_260
    s[15] = ror(tag ^ 0xa5a55a5a, 15) ^ 0xb5470917; // local_25c
    s[16] = 0; // local_258 length accumulator (lo)
    s[17] = 0; // local_258 (hi)
    s[18] = tag; // local_250  (param_1[0x12], "rate" word)
    s[19] = tag ^ 0x9e3779b9; // local_24c (param_1[0x13], counter word)
    s
}

/// The per-slice counter tweak applied to word[19] before each absorb permute
/// (decompile: `local_24c = ror(ctr, ~ctr&0x1f) ^ local_24c ^ 0x4e564d50`).
#[inline]
pub fn counter_tweak(s: &mut [u32; 20], ctr: u32) {
    let r = (!ctr) & 0x1f;
    s[19] = ror(ctr, r) ^ s[19] ^ 0x4e564d50; // "PMVN"
}

/// The final squeeze (decompile tail of `FUN_00ea6f30`): 8×u32 produced from
/// state words s[8..16] with a splitmix/murmur-fmix32 finalizer, keyed by the
/// length accumulator s[16] and the post-absorb counter word s[19].
///
/// `DAT_009218dc` is `DAT_9218d0` shifted by +12 bytes (i.e. `[3..]` of that
/// table); index i uses `mul_tbl[i]` (starting at word 3) and the additive
/// `DAT_9218d0[(i+9)&0xf]`.
pub fn squeeze(s: &[u32; 20]) -> [u8; 32] {
    use super::perm::DAT_9218D0 as MUL;
    let mut out = [0u8; 32];
    let len_acc = s[16];
    let ctr_word = s[19];
    let mut i54: i32 = 5;
    for i in 0..8usize {
        // puVar78 walks &local_278 (=s[8]) forward: current word = s[8+i]
        let w = s[8 + i];
        // puVar1 = puVar78 - 8  => s[8+i-8] = s[i]
        let prev = s[i];
        let rot_c = (i54.wrapping_neg() as u32) & 0x1f;
        // squeeze multiplier: &DAT_009218dc + i  == DAT_9218d0[3 + i]
        let mul = MUL[(3 + i) & 0xf];
        let add = MUL[(i + 9) & 0xf];
        let ctr_rot = {
            let r = (i as u32).wrapping_neg() & 0x1f;
            ror(ctr_word, r)
        };
        let mut v = (mul.wrapping_mul(len_acc) ^ ctr_rot ^ ror(w, rot_c).wrapping_add(prev))
            .wrapping_add(add);
        // splitmix/murmur fmix32
        v = (v ^ (v >> 15)).wrapping_mul(0x85ebca77); // -0x7a143589
        v = (v ^ (v >> 13)).wrapping_mul(0xc2b2ae3d); // -0x3d4d51c3
        v ^= v >> 16;
        out[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
        i54 += 3;
    }
    out
}

/// Absorb a data slice into `state` — direct port of `FUN_00e8e75c(state, dataPtr, len, tweak)`.
///
/// Structure (reversed from decompile at result_id dfdd9bb3f5a12b48):
///   1. First line: `state[tweak&0xf] ^= DAT_9218d0[(tweak>>4)&0xf] * tweak`
///   2. Per 4-byte input block at byte-offset `off` (block = off/4):
///      a. Load 4-byte LE word from data (or accumulate partial bytes)
///      b. Mix: `w += tweak + DAT_9218d0[(block+5)&0xf]*(off as u32) + ror(s[19], block&0x1f)`
///      c. fmix32: `w = (w^w>>15)*0x85ebca77; f = (w^w>>13)*0xc2b2ae3d; f ^= f>>16`
///      d. `s[block&0xf] += f * DAT_9218d0[(block+1)&0xf]`
///      e. `t = f + s[(block+13)&0xf] + s[block&0xf]; r4 = (-(f&0xf)-7)&0x1f`
///      f. `s[(block+7)&0xf] ^= ror(t, r4)`
///      g. `t = f ^ s[(block+3)&0xf]; r9 = (-(f>>5&0xf)-9)&0x1f`
///      h. `s[(block+11)&0xf] += ror(t, r9) * DAT_9218d0[(block+9)&0xf]`
///      i. `r5 = (-((off as u32&0xc)|1))&0x1f; s[19] = ror(f, r5) ^ old_s19`
///      j. If block%16 == 15: permute(&s, 1)
///   3. Final: `s[(len+3)&0xf] ^= DAT_9218d0[(len+11)&0xf] * len as u32; permute(&s, 1)`
pub fn absorb(state: &mut [u32; 20], data: &[u8], tweak: u32) {
    use super::perm::DAT_9218D0 as MUL;
    // Step 1: first line
    let idx = (tweak & 0xf) as usize;
    let k_idx = ((tweak >> 4) & 0xf) as usize;
    state[idx] ^= MUL[k_idx].wrapping_mul(tweak);

    // Step 2: fold each 4-byte block
    let len = data.len();
    let mut off = 0usize; // byte offset (= block*4)
    while off < len {
        // Load block word. Decompile has two paths:
        //   full block  (remaining >= 4): `w = *(u32*)(data + off)` — plain LE load
        //   partial block (remaining < 4): load each byte then OR in sentinel
        //     `w |= (remaining as u32) << 29`  (bits 29-30 encode remaining byte count)
        let remaining = len - off; // uVar35 = param_3 - uVar36
        let w: u32 = if remaining < 4 {
            let mut w: u32 = 0;
            for b in off..len {
                w |= (data[b] as u32) << (((b - off) as u32) << 3);
            }
            w | ((remaining as u32) << 29) // length sentinel (decompile: `uVar30 | (int)uVar35 << 0x1d`)
        } else {
            // Full 4-byte block: direct LE load
            u32::from_le_bytes(data[off..off + 4].try_into().unwrap())
        };
        let mut w = w;
        let block = off >> 2; // block index
        let uvar26 = (block & 0xf) as usize;
        let old_s19 = state[19];

        // The rotation uses the 16-lane index, so it resets when block 16
        // triggers the internal permutation.
        let rot_s19 = (uvar26 as u32).wrapping_neg() & 0x1f;
        w = w
            .wrapping_add(tweak)
            .wrapping_add(MUL[(block.wrapping_add(5)) & 0xf].wrapping_mul(off as u32))
            .wrapping_add(ror(old_s19, rot_s19));

        // fmix32 (murmur/splitmix finalizer)
        w = (w ^ (w >> 15)).wrapping_mul(0x85ebca77u32);
        let f = (w ^ (w >> 13)).wrapping_mul(0xc2b2ae3du32);
        let f = f ^ (f >> 16);

        // Update state[block&0xf]
        let sv = state[uvar26].wrapping_add(f.wrapping_mul(MUL[(block.wrapping_add(1)) & 0xf]));
        state[uvar26] = sv;

        // Update state[(block+7)&0xf] ^= ror(f + s[(block+13)&0xf] + sv, r4)
        let t = f
            .wrapping_add(state[(block.wrapping_add(13)) & 0xf])
            .wrapping_add(sv);
        let r4 = (f & 0xf).wrapping_neg().wrapping_sub(7) & 0x1f;
        let idx7 = (block.wrapping_add(7)) & 0xf;
        state[idx7] ^= ror(t, r4);

        // Update state[(block+11)&0xf] += ror(t2, r9) * MUL[(block+9)&0xf]
        let t2 = f ^ state[(block.wrapping_add(3)) & 0xf];
        let r9 = ((f >> 5) & 0xf).wrapping_neg().wrapping_sub(9) & 0x1f;
        let idx11 = (block.wrapping_add(11)) & 0xf;
        state[idx11] =
            state[idx11].wrapping_add(ror(t2, r9).wrapping_mul(MUL[(block.wrapping_add(9)) & 0xf]));

        // Update s[19]: ror(f, r5) ^ old_s19
        let r5 = (((off as u32) & 0xc) | 1).wrapping_neg() & 0x1f;
        state[19] = ror(f, r5) ^ old_s19;

        // Every 16 blocks: permute
        if uvar26 == 15 {
            permute(state, 1);
        }

        off += 4;
    }

    // Step 3: final — mix length and permute
    let len_idx = (len.wrapping_add(3)) & 0xf;
    state[len_idx] ^= MUL[(len.wrapping_add(11)) & 0xf].wrapping_mul(len as u32);
    permute(state, 1);
}

/// Derive the 32-byte PRF master key from the static [`super::KEY_TABLE`] via the
/// per-byte transform `FUN_00e98ad8(0x69, i, table[i])`.
///
/// Pragmatic: the master key is hardcoded from the deterministic harness capture
/// (`b14a9fb1...`). The SPN `FUN_00e98ad8` is not yet reversed but is unnecessary
/// for the config AEAD (the master key feeds the key schedule, not the PRF seed).
/// Hardcoded 64B binary master key (from harness KEYBLOB capture):
pub const MASTER_KEY_64: [u8; 64] = [
    0xb1, 0x4a, 0x9f, 0xb1, 0xa9, 0x40, 0x53, 0x91, 0x7a, 0xb0, 0xd8, 0x28, 0x19, 0x02, 0x7f, 0x57,
    0x78, 0x32, 0xe6, 0x02, 0x0c, 0x51, 0xf3, 0x71, 0xc2, 0xcb, 0x41, 0xf5, 0x7b, 0xcd, 0x6a, 0x39,
    0x76, 0x77, 0xe6, 0xab, 0x34, 0x9f, 0xa1, 0x05, 0x00, 0xe3, 0x05, 0x64, 0x12, 0x9c, 0xe4, 0x67,
    0xbc, 0xc2, 0xcb, 0xb0, 0x90, 0x72, 0x57, 0xc8, 0x3d, 0xf0, 0x32, 0x81, 0x5c, 0x4b, 0xbd, 0xa3,
];

/// Full PRF (`FUN_00e8e5a4` / `FUN_00ea6f30`) — domain-separated sponge PRF.
///
/// Per-slice structure (recovered bit-exact from harness_trace2.log):
///   1. Compute `len_bytes = (slice.len() as u64).to_le_bytes()` (8-byte LE prefix)
///   2. `tweak_len  = counter * 0x9e3779b1 + 0x51ed270b + tag`  (wrapping)
///   3. `absorb(state, len_bytes, tweak_len)`   — ends with internal permute(1)
///   4. `tweak_data = (slice.len() as u32) + counter * 0x85ebca77 + 0x2c1b3c6d + tag`
///   5. `absorb(state, slice,     tweak_data)`  — ends with internal permute(1)
///   6. `permute(state, 3)`                     — extra driver-level inter-slice pass
///
/// After all `count` slices, the successful proof path applies stage-6's
/// state-lane mix (`FUN_00eac798`) and a ten-pass permutation before squeeze.
/// The `count ^ 0xfeed1337` counter envelope is only the failure fallback and
/// is intentionally not part of the normal PRF output.
pub fn prf(tag: u32, slices: &[&[u8]]) -> [u8; 32] {
    let mut s = seed_state(tag);
    for (i, slice) in slices.iter().enumerate() {
        let counter = i as u32;
        // length-prefix absorb
        let len_bytes = (slice.len() as u64).to_le_bytes();
        let tweak_len = counter
            .wrapping_mul(0x9e3779b1)
            .wrapping_add(0x51ed270b)
            .wrapping_add(tag);
        absorb(&mut s, &len_bytes, tweak_len);
        // data absorb
        let tweak_data = (slice.len() as u32)
            .wrapping_add(counter.wrapping_mul(0x85ebca77))
            .wrapping_add(0x2c1b3c6d)
            .wrapping_add(tag);
        absorb(&mut s, slice, tweak_data);
        // driver updates length accumulator (s[16] += data_len; s[17] stays 0)
        s[16] = s[16].wrapping_add(slice.len() as u32);
        // driver-level inter-slice permute (n=3, on top of absorb's own n=1)
        permute(&mut s, 3);
    }

    // Successful final proof action, FUN_00eac798(param_2=6):
    //   state[2]  ^= state[16]
    //   state[6]  += state[17] * 0x7feb352d
    //   state[10] ^= count * 0xb492b66f
    //   state[14] += state[19] * 0x91e10da5
    //   permute(state, 10)
    // `state[17]` is normally zero, but retain the complete 32-bit formula.
    let count = slices.len() as u32;
    s[2] ^= s[16];
    s[6] = s[6].wrapping_add(s[17].wrapping_mul(0x7feb_352d));
    s[10] ^= count.wrapping_mul(0xb492_b66f);
    s[14] = s[14].wrapping_add(s[19].wrapping_mul(0x91e1_0da5));
    permute(&mut s, 10);
    squeeze(&s)
}

/// Legacy stub kept for compatibility — was the incorrect first-draft PRF.
/// Use `prf()` for all new callers.
#[allow(dead_code)]
pub fn prf_sublane(tag: u32, inputs: &[(&[u8], u32)]) -> [u8; 32] {
    prf(tag, &inputs.iter().map(|(d, _)| *d).collect::<Vec<_>>())
}

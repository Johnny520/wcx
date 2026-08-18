//! Exact port of FUN_00e8ec3c — the 20-word (u32[20]) ARX permutation core of
//! Nuke's custom PRF. Translated line-for-line from the Ghidra decompilation
//! (see nuke_deobf_clean/perm_FUN_00e8ec3c.txt).
//!
//! State is `s: [u32; 20]`. Note index 0x12=18, 0x13=19 are the rate/counter words.
//! `n` = number of passes (decompile `param_2`).

#[inline(always)]
pub fn ror(x: u32, r: u32) -> u32 {
    x.rotate_right(r & 31)
}

/// Round-constant / IV table `DAT_00921910` (16 × u32, little-endian loads).
pub const DAT_921910: [u32; 16] = [
    0x243f6a88, 0x85a308d3, 0x13198a2e, 0x03707344, 0xa4093822, 0x299f31d0, 0x082efa98, 0xec4e6c89,
    0x452821e6, 0x38d01377, 0xbe5466cf, 0x34e90c6c, 0xc0ac29b7, 0xc97c50dd, 0x3f84d5b5, 0xb5470917,
];

/// ARX multiplier table `DAT_009218d0` (16 × u32).
pub const DAT_9218D0: [u32; 16] = [
    0x9e3779b1, 0x85ebca77, 0xc2b2ae3d, 0x27d4eb2f, 0x165667b1, 0x7feb352d, 0x846ca68b, 0xd6e8feb9,
    0xa5cb9243, 0xb492b66f, 0x9ae16a3b, 0xc13fa9a9, 0x91e10da5, 0xd192ed03, 0xaa78edd7, 0xe9846af9,
];

/// The permutation: `n` outer passes. Direct transcription of FUN_00e8ec3c.
pub fn permute(s: &mut [u32; 20], n: u64) {
    if n == 0 {
        return;
    }
    let mut x = s[0x13];
    let mut u33: u64 = 0; // uVar33
    let mut u29: u64 = 0; // uVar29 (pass counter)
    let mut i34: i32 = 1; // iVar34
    loop {
        let i28 = u29 as u32; // iVar28 = (int)uVar29
        let mut u13 = s[0];
        let mut u15 = s[1];
        // uVar32 = DAT_9218d0[(i28 + x) & 0xf] + i28*0x9e3779b1 + ror(s[0x12], (-i28)&0x1f)
        x = DAT_9218D0[((i28.wrapping_add(x)) & 0xf) as usize]
            .wrapping_add(i28.wrapping_mul(0x9e3779b1))
            .wrapping_add(ror(s[0x12], i28.wrapping_neg()));
        let mut u21 = ror(x, 29); // uVar32 >> 0x1d | uVar32 * 8  == rol3 == ror29
        let mut u3 = x.wrapping_add(u13).wrapping_add(s[0xc]);
        let mut u22 = ror(x, 25); // rol7
        let mut u7 = s[4] ^ ror(u3, 25); // uVar3 >> 0x19 | *0x80 = rol7 = ror25
        u3 = u15.wrapping_add(s[0xd]).wrapping_add(u21);
        let u8 = s[5] ^ ror(u3, 25);
        let mut u17 = s[8].wrapping_add(u7.wrapping_mul(0xa5cb9243)); // -0x5a346dbd
        let mut u14 = s[2];
        let mut u16 = s[3];
        let mut u23 = ror(x, 21); // rol11
        let mut u18 = s[9].wrapping_add(u8.wrapping_mul(0x9ae16a3b)); // -0x651e95c5
        u3 = u14.wrapping_add(s[0xe]).wrapping_add(u22);
        let mut u4 = u18.wrapping_add(u15);
        let mut u5 = u16.wrapping_add(s[0xf]).wrapping_add(u23);
        let mut u9 = s[6] ^ ror(u3, 25);
        u3 = u17.wrapping_add(u13);
        let mut u10 = s[0xd] ^ ror(u4, 21); // uVar4 >> 0x15 | *0x800 = rol11 = ror21
        let mut u11 = s[0xc] ^ ror(u3, 21);
        let mut u19 = s[10].wrapping_add(u9.wrapping_mul(0x91e10da5)); // -0x6e1ef25b
        let mut u12 = s[7] ^ ror(u5, 25);
        u13 = u13.wrapping_add(u11.wrapping_mul(0x9e3779b1)); // -0x61c8864f
        u3 = u7.wrapping_add(x).wrapping_add(u11);
        let mut u20 = s[0xb].wrapping_add(u12.wrapping_mul(0xaa78edd7)); // -0x55871229
        u15 = u15.wrapping_add(u10.wrapping_mul(0xc2b2ae3d)); // -0x3d4d51c3
        u17 ^= ror(u3, 15); // >>0xf | <<0x11 = rol17 = ror15
        u3 = u8.wrapping_add(u21).wrapping_add(u10);
        u4 = u19.wrapping_add(u14);
        u18 ^= ror(u3, 15);
        u21 = s[0xe] ^ ror(u4, 21);
        u3 = u17.wrapping_add(u13);
        u4 = u20.wrapping_add(u16);
        u13 ^= ror(u3, 9); // >>9 | <<0x17 = rol23 = ror9
        u3 = u18.wrapping_add(u15);
        u14 = u14.wrapping_add(u21.wrapping_mul(0x165667b1));
        u5 = u9.wrapping_add(u22).wrapping_add(u21);
        u22 = s[0xf] ^ ror(u4, 21);
        u19 ^= ror(u5, 15);
        u15 ^= ror(u3, 9);
        u11 = u11.wrapping_add(u13.wrapping_mul(0x91e10da5)); // -0x6e1ef25b
        let u24 = ror(x, 15); // rol17
        u3 = u12.wrapping_add(u23).wrapping_add(u22);
        u10 = u10.wrapping_add(u15.wrapping_mul(0xaa78edd7)); // -0x55871229
        u20 ^= ror(u3, 15);
        u3 = u19.wrapping_add(u14);
        u14 ^= ror(u3, 9);
        u3 = u11.wrapping_add(u24).wrapping_add(u15);
        u23 = ror(x, 13); // >>0xd | <<0x13 = rol19 = ror13
        u4 = u9.wrapping_add(u19.wrapping_mul(0x9e3779b1)) ^ ror(u3, 25);
        u3 = u10.wrapping_add(u23).wrapping_add(u14);
        u16 = u16.wrapping_add(u22.wrapping_mul(0x846ca68b)); // -0x7b935975
        u5 = u12.wrapping_add(u20.wrapping_mul(0xc2b2ae3d)) ^ ror(u3, 25); // -0x3d4d51c3
        u9 = u20.wrapping_add(u4.wrapping_mul(0x91e10da5)); // -0x6e1ef25b
        u20 = u20.wrapping_add(u16);
        u16 ^= ror(u20, 9);
        u3 = u9.wrapping_add(u15);
        u11 ^= ror(u3, 21);
        let u25 = ror(x, 9); // rol23
        u12 = u17.wrapping_add(u5.wrapping_mul(0x9ae16a3b)); // -0x651e95c5
        let u26 = ror(x, 19); // >>0x13 | <<0x2000... wait: uVar32>>0x13 | uVar32*0x2000 = rol13 = ror19
        u22 = u22.wrapping_add(u16.wrapping_mul(0xc2b2ae3d)); // -0x3d4d51c3
        x = u4.wrapping_add(u24).wrapping_add(u11);
        u21 = u21.wrapping_add(u14.wrapping_mul(0x9e3779b1)); // -0x61c8864f
        u9 ^= ror(x, 15);
        x = u12.wrapping_add(u14);
        u15 = u15.wrapping_add(u11.wrapping_mul(0xc2b2ae3d)); // -0x3d4d51c3
        u10 ^= ror(x, 21);
        x = u13.wrapping_add(u26).wrapping_add(u22);
        u3 = u21.wrapping_add(u25).wrapping_add(u16);
        u20 = u8.wrapping_add(u18.wrapping_mul(0xaa78edd7)) ^ ror(x, 25); // -0x55871229
        u7 = u7.wrapping_add(u17.wrapping_mul(0x91e10da5)) ^ ror(u3, 25); // -0x6e1ef25b
        x = u5.wrapping_add(u23).wrapping_add(u10);
        u12 ^= ror(x, 15);
        x = u9.wrapping_add(u15);
        u18 = u18.wrapping_add(u7.wrapping_mul(0x91e10da5)); // -0x6e1ef25b
        u15 ^= ror(x, 9);
        u14 = u14.wrapping_add(u10.wrapping_mul(0x165667b1));
        u19 = u19.wrapping_add(u20.wrapping_mul(0x9ae16a3b)); // -0x651e95c5
        x = u12.wrapping_add(u14);
        u14 ^= ror(x, 9);
        x = u18.wrapping_add(u16);
        u21 ^= ror(x, 21);
        // ── store-back region (decompile lines 134-161), transcribed IN ORDER ──
        x = u19.wrapping_add(u13); // line 134: uVar32 = uVar19 + uVar13
        s[6] = u4.wrapping_add(u9.wrapping_mul(0xd192ed03)); // 135: -0x2e6d12fd
        s[7] = u5.wrapping_add(u12.wrapping_mul(0xe9846af9)); // 136: -0x167b9507
        u22 ^= ror(x, 21); // 137: uVar22 ^= rol11(uVar32)
        s[1] = u15; // 138
        s[2] = u14; // 139
        u16 = u16.wrapping_add(u21.wrapping_mul(0xc2b2ae3d)); // 140: -0x3d4d51c3
        x = u7.wrapping_add(u25).wrapping_add(u21); // 141
        s[0xb] = u9; // 142
        s[0xc] = u11.wrapping_add(u15.wrapping_mul(0x85ebca77)); // 143: -0x7a143589
        u18 ^= ror(x, 15); // 144
        x = u18.wrapping_add(u16); // 145
        s[8] = u12; // 146
        s[9] = u18; // 147
        u3 = u20.wrapping_add(u26).wrapping_add(u22); // 148
        u16 ^= ror(x, 9); // 149
        u13 = u13.wrapping_add(u22.wrapping_mul(0x165667b1)); // 150
        u19 ^= ror(u3, 15); // 151
        s[10] = u19; // 152
        x = u19.wrapping_add(u13); // 153
        s[3] = u16; // 154
        u13 ^= ror(x, 9); // 155
        s[0] = u13; // 156
        s[4] = u7.wrapping_add(u18.wrapping_mul(0x85ebca77)); // 157: -0x7a143589
        s[5] = u20.wrapping_add(u19.wrapping_mul(0xe9846af9)); // 158: -0x167b9507
        s[0xf] = u22.wrapping_add(u13.wrapping_mul(0xe9846af9)); // 159: -0x167b9507
        s[0xd] = u10.wrapping_add(u14.wrapping_mul(0xe9846af9)); // 160: -0x167b9507
        s[0xe] = u21.wrapping_add(u16.wrapping_mul(0xd192ed03)); // 161: -0x2e6d12fd

        // Phase C — lane diffusion / permute-16 (lines 165-183)
        let mut u30: u64 = u33;
        let mut lv36: i64 = 0;
        let mut i1: i32 = i34;
        loop {
            let i35 = lv36 as u32;
            // uVar31 = uVar30 - (uVar30 * 0x842108421084211) >> 64 ... this is a div-by-31 magic.
            // iVar6 = i1 + ((int)((uVar31>>1) + hi) >> 4)
            let hi = ((u30 as u128).wrapping_mul(0x0842108421084211u128) >> 64) as u64;
            let u31 = u30.wrapping_sub(hi);
            u30 = u30.wrapping_add(7);
            let i6 = (i1 as i64 + ((((u31 >> 1).wrapping_add(hi)) as u32 >> 4) as i64)) as i32;
            i1 = i1.wrapping_add(7);
            let idx_b = ((i35.wrapping_add(0xb)) & 0xf) as usize;
            let idx_5 = ((i35.wrapping_add(5)) & 0xf) as usize;
            let mut u32v = (s[idx_b] ^ s[idx_5] ^ ror(s[0x13], i35.wrapping_neg()))
                .wrapping_add(s[lv36 as usize])
                .wrapping_add(DAT_921910[lv36 as usize]);
            let u3b = i6.wrapping_neg() as u32 & 0x1f;
            s[lv36 as usize] =
                ror(u32v, u3b).wrapping_mul(DAT_9218D0[((i28.wrapping_add(i35)) & 0xf) as usize]);
            let _ = &mut u32v;
            lv36 += 1;
            if lv36 == 0x10 {
                break;
            }
        }

        // Phase D — counter update (lines 184-191)
        let u30f = (u29 & 0xf) as usize;
        u29 = u29.wrapping_add(1);
        i34 = i34.wrapping_add(3);
        u33 = u33.wrapping_add(3);
        x = s[u30f] ^ ror(s[0x13], 27) ^ ror(s[((i28.wrapping_add(9)) & 0xf) as usize], 21);
        s[0x13] = x;

        if u29 == n {
            break;
        }
    }
}

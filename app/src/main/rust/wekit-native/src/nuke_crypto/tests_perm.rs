//! Validate perm::permute against a captured FUN_00e8ec3c vector (n=1).
use super::perm::permute;

fn hexd(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn to_words(b: &[u8]) -> [u32; 20] {
    let mut s = [0u32; 20];
    for i in 0..20 {
        s[i] = u32::from_le_bytes([b[i * 4], b[i * 4 + 1], b[i * 4 + 2], b[i * 4 + 3]]);
    }
    s
}

pub fn run() -> bool {
    // Captured PERM #1 (n=1)
    let inp = hexd(
        "257cba3ed73adcd02e8a1913e0d9169e223809a4d0319f2998fa2e08cb57210b673668977713d038cf6654be5a2660a97ba0af09dd507cc9d78ecff9c7de94a7000000000000000033534d4ec3225e2b",
    );
    let out = hexd(
        "eaa8e84743efcdf99bf2061356d7eaa3aa841f8c2693915909c439d00ccb2e7a3bcce04d38feb4891497f4954f7e9b0c847ccc8621109a18d118ca765d2c7822000000000000000033534d4ec234dd8b",
    );
    let mut s = to_words(&inp);
    let expect = to_words(&out);
    permute(&mut s, 1);
    if s == expect {
        println!("[ok]  perm vector #1 matches");
        true
    } else {
        println!("[FAIL] perm vector #1 mismatch:");
        for i in 0..20 {
            let mark = if s[i] == expect[i] { " " } else { "X" };
            println!("  {mark} s[{i:2}] got={:08x} exp={:08x}", s[i], expect[i]);
        }
        false
    }
}

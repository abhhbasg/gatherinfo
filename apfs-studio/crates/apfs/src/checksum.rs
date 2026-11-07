//! Fletcher-64 object checksums and CRC-32C (Castagnoli) used for name hashes.

const MOD: u64 = 0xFFFF_FFFF;

/// Computes the APFS Fletcher-64 checksum of `data` (which must NOT include
/// the 8 checksum bytes at the beginning of the object).
pub fn fletcher64(data: &[u8]) -> u64 {
    let mut sum1: u64 = 0;
    let mut sum2: u64 = 0;
    // Process in groups so the accumulators cannot overflow before reduction.
    for group in data.chunks(4 * 1024) {
        for w in group.chunks_exact(4) {
            let v = u32::from_le_bytes([w[0], w[1], w[2], w[3]]) as u64;
            sum1 += v;
            sum2 += sum1;
        }
        sum1 %= MOD;
        sum2 %= MOD;
    }
    let c1 = MOD - ((sum1 + sum2) % MOD);
    let c2 = MOD - ((sum1 + c1) % MOD);
    (c2 << 32) | c1
}

/// Verifies the checksum of a full object buffer.
pub fn verify_object(buf: &[u8]) -> bool {
    if buf.len() < 32 || buf.len() % 4 != 0 {
        return false;
    }
    let stored = u64::from_le_bytes(buf[0..8].try_into().unwrap());
    // An all-zero block is never valid even though its checksum math might work out.
    if stored == 0 && buf.iter().all(|&b| b == 0) {
        return false;
    }
    fletcher64(&buf[8..]) == stored
}

/// Recomputes and stores the checksum of a full object buffer.
pub fn seal_object(buf: &mut [u8]) {
    let c = fletcher64(&buf[8..]);
    buf[0..8].copy_from_slice(&c.to_le_bytes());
}

static CRC32C_TABLE: [u32; 256] = {
    let mut t = [0u32; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = i as u32;
        let mut k = 0;
        while k < 8 {
            c = if c & 1 != 0 { 0x82F6_3B78 ^ (c >> 1) } else { c >> 1 };
            k += 1;
        }
        t[i] = c;
        i += 1;
    }
    t
};

/// Raw CRC-32C update (no pre/post inversion), matching the Linux kernel's
/// `crc32c(seed, data, len)` semantics which APFS name hashing relies on.
pub fn crc32c_update(mut crc: u32, data: &[u8]) -> u32 {
    for &b in data {
        crc = CRC32C_TABLE[((crc ^ b as u32) & 0xFF) as usize] ^ (crc >> 8);
    }
    crc
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32c_standard_vector() {
        // Standard CRC-32C("123456789") = 0xE3069283 with init/final inversion.
        let c = !crc32c_update(!0, b"123456789");
        assert_eq!(c, 0xE306_9283);
    }

    #[test]
    fn fletcher_roundtrip() {
        let mut b = vec![0u8; 4096];
        for (i, x) in b.iter_mut().enumerate() {
            *x = (i * 7 % 251) as u8;
        }
        seal_object(&mut b);
        assert!(verify_object(&b));
        b[100] ^= 1;
        assert!(!verify_object(&b));
    }
}

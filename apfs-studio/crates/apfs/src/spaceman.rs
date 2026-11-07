//! Space manager: block allocation bitmaps, chunk-info blocks and the
//! internal pool, with copy-on-write commit.

use crate::bytes::*;
use crate::checksum;
use crate::container::Container;
use crate::error::{Error, Result};
use crate::format::*;
use std::collections::{BTreeSet, HashMap};

struct AddrBlock {
    paddr: u64,
    data: Vec<u8>,
    dirty: bool,
}

#[derive(Clone, Copy)]
struct ChunkLoc {
    cib: usize,
    slot: usize,
}

pub struct Spaceman {
    pub oid: u64,
    pub data: Vec<u8>,
    bs: usize,
    bpc: u64,
    block_count: u64,
    chunk_count: u64,
    cabs: Vec<AddrBlock>,
    /// For each CIB: its block and (cab index, slot) when CABs are used.
    cibs: Vec<(AddrBlock, Option<(usize, usize)>)>,
    chunks: Vec<ChunkLoc>,
    bitmaps: HashMap<usize, Vec<u8>>,
    dirty_chunks: BTreeSet<usize>,
    pending_free: Vec<(u64, u64)>,
    free_count: u64,
    hint: u64,
    // internal pool
    ip_base: u64,
    ip_count: u64,
    ip_bm: Vec<u8>,
    ip_dirty: bool,
    ip_pending_free: Vec<u64>,
    /// Blocks allocated during this transaction (for diagnostics/limits).
    pub allocated: u64,
}

impl Spaceman {
    pub fn load(c: &Container) -> Result<Self> {
        let sm = c
            .ephemeral(c.spaceman_oid())
            .ok_or_else(|| Error::Corrupt("space manager missing".into()))?;
        let d = sm.data.clone();
        let bs = c.block_size as usize;
        if u32_at(&d, sm::BLOCK_SIZE)? as usize != bs {
            return Err(Error::Corrupt("space manager block size mismatch".into()));
        }
        let bpc = u32_at(&d, sm::BLOCKS_PER_CHUNK)? as u64;
        let cpc = u32_at(&d, sm::CHUNKS_PER_CIB)? as usize;
        let cpcab = u32_at(&d, sm::CIBS_PER_CAB)? as usize;
        if bpc != bs as u64 * 8 || cpc == 0 || cpcab == 0 {
            return Err(Error::Corrupt("space manager geometry is invalid".into()));
        }
        let dev = sm::DEV;
        let block_count = u64_at(&d, dev + sm::D_BLOCK_COUNT)?;
        let chunk_count = u64_at(&d, dev + sm::D_CHUNK_COUNT)?;
        let cib_count = u32_at(&d, dev + sm::D_CIB_COUNT)? as usize;
        let cab_count = u32_at(&d, dev + sm::D_CAB_COUNT)? as usize;
        let free_count = u64_at(&d, dev + sm::D_FREE_COUNT)?;
        let addr_off = u32_at(&d, dev + sm::D_ADDR_OFFSET)? as usize;
        let t2 = sm::DEV + sm::DEV_SIZE;
        if u64_at(&d, t2 + sm::D_BLOCK_COUNT)? != 0 {
            return Err(Error::Unsupported("tier-2 (Fusion) space manager".into()));
        }
        if block_count != c.block_count {
            return Err(Error::Corrupt("space manager block count differs from the container".into()));
        }
        if chunk_count != block_count.div_ceil(bpc) || cib_count as u64 != chunk_count.div_ceil(cpc as u64) {
            return Err(Error::Corrupt("space manager chunk counts are inconsistent".into()));
        }

        let read_obj = |paddr: u64, ty: u32| -> Result<Vec<u8>> {
            let (h, b) = c.read_object(paddr, "space manager block", Some(ty))?;
            if h.oid != paddr {
                return Err(Error::Corrupt(format!("space manager block {paddr:#x} has wrong oid")));
            }
            Ok(b)
        };

        let mut cabs = Vec::new();
        let mut cib_addrs: Vec<(u64, Option<(usize, usize)>)> = Vec::new();
        if cab_count == 0 {
            for i in 0..cib_count {
                cib_addrs.push((u64_at(&d, addr_off + i * 8)?, None));
            }
        } else {
            for i in 0..cab_count {
                let pa = u64_at(&d, addr_off + i * 8)?;
                let b = read_obj(pa, OT_SPACEMAN_CAB)?;
                if u32_at(&b, 0x20)? as usize != i {
                    return Err(Error::Corrupt("CIB address block index mismatch".into()));
                }
                let n = u32_at(&b, 0x24)? as usize;
                if n > cpcab {
                    return Err(Error::Corrupt("CIB address block holds too many entries".into()));
                }
                for j in 0..n {
                    cib_addrs.push((u64_at(&b, 0x28 + j * 8)?, Some((i, j))));
                }
                cabs.push(AddrBlock { paddr: pa, data: b, dirty: false });
            }
        }
        if cib_addrs.len() != cib_count {
            return Err(Error::Corrupt("chunk-info block count mismatch".into()));
        }
        let mut cibs = Vec::with_capacity(cib_count);
        let mut chunks = Vec::with_capacity(chunk_count as usize);
        let mut total_free = 0u64;
        for (ci, (pa, cab)) in cib_addrs.into_iter().enumerate() {
            let b = read_obj(pa, OT_SPACEMAN_CIB)?;
            if u32_at(&b, 0x20)? as usize != ci {
                return Err(Error::Corrupt("chunk-info block index mismatch".into()));
            }
            let n = u32_at(&b, 0x24)? as usize;
            if n > cpc || 0x28 + n * 32 > bs {
                return Err(Error::Corrupt("chunk-info block holds too many chunks".into()));
            }
            for s in 0..n {
                let e = 0x28 + s * 32;
                let addr = rd64(&b, e + 8);
                if addr != chunks.len() as u64 * bpc {
                    return Err(Error::Corrupt("chunk-info entries are not consecutive".into()));
                }
                total_free += (rd32(&b, e + 20) & 0x000F_FFFF) as u64;
                chunks.push(ChunkLoc { cib: ci, slot: s });
            }
            cibs.push((AddrBlock { paddr: pa, data: b, dirty: false }, cab));
        }
        if chunks.len() as u64 != chunk_count {
            return Err(Error::Corrupt("chunk count mismatch".into()));
        }
        if total_free != free_count {
            return Err(Error::Corrupt(format!(
                "space manager free count {free_count} does not match chunk totals {total_free}"
            )));
        }

        // Internal pool bitmap.
        let ip_base = u64_at(&d, sm::IP_BASE)?;
        let ip_count = u64_at(&d, sm::IP_BLOCK_COUNT)?;
        let ip_bm_size = u32_at(&d, sm::IP_BM_SIZE_IN_BLOCKS)? as usize;
        let ip_bm_base = u64_at(&d, sm::IP_BM_BASE)?;
        let ip_bm_count = u32_at(&d, sm::IP_BM_BLOCK_COUNT)? as u64;
        let bm_off = u32_at(&d, sm::IP_BITMAP_OFFSET)? as usize;
        if ip_bm_size == 0 || (ip_bm_size * bs * 8) < ip_count as usize || ip_bm_count == 0 {
            return Err(Error::Corrupt("internal pool bitmap geometry is invalid".into()));
        }
        let mut ip_bm = vec![0u8; ip_bm_size * bs];
        for i in 0..ip_bm_size {
            let slot = u16_at(&d, bm_off + i * 2)? as u64;
            if slot >= ip_bm_count {
                return Err(Error::Corrupt("internal pool bitmap index out of range".into()));
            }
            let b = c.read_block_raw(ip_bm_base + slot)?;
            ip_bm[i * bs..(i + 1) * bs].copy_from_slice(&b);
        }

        let s = Self {
            oid: sm.oid,
            data: d,
            bs,
            bpc,
            block_count,
            chunk_count,
            cabs,
            cibs,
            chunks,
            bitmaps: HashMap::new(),
            dirty_chunks: BTreeSet::new(),
            pending_free: Vec::new(),
            free_count,
            hint: 0,
            ip_base,
            ip_count,
            ip_bm,
            ip_dirty: false,
            ip_pending_free: Vec::new(),
            allocated: 0,
        };
        // Sanity: every CIB / CAB / bitmap block must be marked used in the IP.
        let mut meta: Vec<u64> = s.cibs.iter().map(|(b, _)| b.paddr).collect();
        meta.extend(s.cabs.iter().map(|b| b.paddr));
        for ch in 0..s.chunks.len() {
            let bm = s.ci_bitmap_addr(ch);
            if bm != 0 {
                meta.push(bm);
            }
        }
        for pa in meta {
            if !s.in_ip(pa) || !s.ip_test(pa - s.ip_base) {
                return Err(Error::Corrupt(format!("space manager block {pa:#x} is not allocated in the internal pool")));
            }
        }
        Ok(s)
    }

    fn in_ip(&self, pa: u64) -> bool {
        pa >= self.ip_base && pa < self.ip_base + self.ip_count
    }

    fn ip_test(&self, i: u64) -> bool {
        self.ip_bm[(i / 8) as usize] & (1 << (i % 8)) != 0
    }

    fn ip_set(&mut self, i: u64, v: bool) {
        let b = &mut self.ip_bm[(i / 8) as usize];
        if v {
            *b |= 1 << (i % 8);
        } else {
            *b &= !(1 << (i % 8));
        }
        self.ip_dirty = true;
    }

    fn ci(&self, ch: usize) -> usize {
        0x28 + self.chunks[ch].slot * 32
    }
    fn ci_block_count(&self, ch: usize) -> u64 {
        let l = self.chunks[ch];
        rd32(&self.cibs[l.cib].0.data, self.ci(ch) + 16) as u64
    }
    fn ci_free(&self, ch: usize) -> u64 {
        let l = self.chunks[ch];
        (rd32(&self.cibs[l.cib].0.data, self.ci(ch) + 20) & 0x000F_FFFF) as u64
    }
    fn ci_bitmap_addr(&self, ch: usize) -> u64 {
        let l = self.chunks[ch];
        rd64(&self.cibs[l.cib].0.data, self.ci(ch) + 24)
    }
    fn set_ci_free(&mut self, ch: usize, v: u64) {
        let l = self.chunks[ch];
        let off = self.ci(ch) + 20;
        let d = &mut self.cibs[l.cib].0.data;
        let old = rd32(d, off);
        wr32(d, off, (old & 0xFFF0_0000) | (v as u32 & 0x000F_FFFF));
        self.cibs[l.cib].0.dirty = true;
    }

    fn bitmap(&mut self, c: &Container, ch: usize) -> Result<&mut Vec<u8>> {
        if !self.bitmaps.contains_key(&ch) {
            let pa = self.ci_bitmap_addr(ch);
            let bm = if pa == 0 { vec![0u8; self.bs] } else { c.read_block_raw(pa)? };
            let blocks = self.ci_block_count(ch);
            let used: u64 = (0..blocks).filter(|&i| bm[(i / 8) as usize] & (1 << (i % 8)) != 0).count() as u64;
            if blocks - used != self.ci_free(ch) {
                return Err(Error::Corrupt(format!(
                    "allocation bitmap of chunk {ch} disagrees with its free count"
                )));
            }
            self.bitmaps.insert(ch, bm);
        }
        Ok(self.bitmaps.get_mut(&ch).unwrap())
    }

    pub fn free_blocks(&self) -> u64 {
        self.free_count
    }

    /// Is the block currently allocated (according to the in-memory state)?
    pub fn is_allocated(&mut self, c: &Container, paddr: u64) -> Result<bool> {
        let ch = (paddr / self.bpc) as usize;
        let i = paddr % self.bpc;
        let bm = self.bitmap(c, ch)?;
        Ok(bm[(i / 8) as usize] & (1 << (i % 8)) != 0)
    }

    /// Allocates up to `max` contiguous blocks (at least one).
    pub fn alloc(&mut self, c: &Container, max: u64) -> Result<(u64, u64)> {
        if self.free_count == 0 || max == 0 {
            return Err(Error::NoSpace);
        }
        let nchunks = self.chunk_count as usize;
        let start_ch = ((self.hint / self.bpc) as usize).min(nchunks.saturating_sub(1));
        for step in 0..=nchunks {
            let ch = (start_ch + step) % nchunks;
            if self.ci_free(ch) == 0 {
                continue;
            }
            let blocks = self.ci_block_count(ch);
            let from = if step == 0 { self.hint % self.bpc } else { 0 };
            let bm = self.bitmap(c, ch)?;
            let test = |bm: &Vec<u8>, i: u64| bm[(i / 8) as usize] & (1 << (i % 8)) != 0;
            let mut i = from;
            // Skip fully used bytes quickly.
            while i < blocks {
                if i % 8 == 0 && bm[(i / 8) as usize] == 0xFF {
                    i += 8;
                    continue;
                }
                if !test(bm, i) {
                    break;
                }
                i += 1;
            }
            if i >= blocks {
                if step == 0 && from > 0 {
                    // Retry this chunk from its beginning at the end of the loop.
                    continue;
                }
                continue;
            }
            let mut n = 0;
            while i + n < blocks && n < max && !test(bm, i + n) {
                n += 1;
            }
            for k in i..i + n {
                bm[(k / 8) as usize] |= 1 << (k % 8);
            }
            let free = self.ci_free(ch) - n;
            self.set_ci_free(ch, free);
            self.dirty_chunks.insert(ch);
            self.free_count -= n;
            let paddr = ch as u64 * self.bpc + i;
            self.hint = paddr + n;
            self.allocated += n;
            return Ok((paddr, n));
        }
        // The hint may have skipped free space at the start of the first chunk.
        if self.hint != 0 {
            self.hint = 0;
            return self.alloc(c, max);
        }
        Err(Error::NoSpace)
    }

    pub fn alloc_one(&mut self, c: &Container) -> Result<u64> {
        Ok(self.alloc(c, 1)?.0)
    }

    /// Schedules blocks to be freed when the transaction commits. They stay
    /// allocated until then, so they cannot be reused by this transaction.
    pub fn free_later(&mut self, paddr: u64, n: u64) {
        if n > 0 {
            self.pending_free.push((paddr, n));
        }
    }

    /// Frees blocks immediately (only for blocks no checkpoint references).
    pub fn free_now(&mut self, c: &Container, paddr: u64, n: u64) -> Result<()> {
        if paddr.checked_add(n).map_or(true, |e| e > self.block_count) {
            return Err(Error::Corrupt(format!("freeing out-of-range blocks {paddr:#x}+{n}")));
        }
        let mut p = paddr;
        let end = paddr + n;
        while p < end {
            let ch = (p / self.bpc) as usize;
            let bpc = self.bpc;
            let chunk_end = ((ch as u64 + 1) * bpc).min(end);
            let bm = self.bitmap(c, ch)?;
            let mut cnt = 0;
            for b in p..chunk_end {
                let i = b % bpc;
                let byte = &mut bm[(i / 8) as usize];
                if *byte & (1 << (i % 8)) == 0 {
                    return Err(Error::Corrupt(format!("double free of block {b:#x}")));
                }
                *byte &= !(1 << (i % 8));
                cnt += 1;
            }
            let free = self.ci_free(ch) + cnt;
            self.set_ci_free(ch, free);
            self.dirty_chunks.insert(ch);
            self.free_count += cnt;
            p = chunk_end;
        }
        Ok(())
    }

    pub fn ip_contains(&self, paddr: u64) -> bool {
        self.in_ip(paddr)
    }

    /// Frees an internal-pool block immediately.
    pub fn ip_free_now(&mut self, paddr: u64) -> Result<()> {
        if !self.in_ip(paddr) {
            return Err(Error::Corrupt("internal pool free outside the pool".into()));
        }
        let i = paddr - self.ip_base;
        if !self.ip_test(i) {
            return Err(Error::Corrupt(format!("double free of internal pool block {paddr:#x}")));
        }
        self.ip_set(i, false);
        Ok(())
    }

    fn ip_alloc(&mut self) -> Result<u64> {
        for i in 0..self.ip_count {
            if !self.ip_test(i) {
                self.ip_set(i, true);
                return Ok(self.ip_base + i);
            }
        }
        Err(Error::NoSpace)
    }

    /// Writes all modified allocation structures (copy-on-write into the
    /// internal pool) and updates the in-memory space manager object.
    pub fn commit(&mut self, c: &Container, xid: u64) -> Result<()> {
        let bs = self.bs;
        // 1. Apply deferred frees.
        let pending = std::mem::take(&mut self.pending_free);
        for (p, n) in pending {
            self.free_now(c, p, n)?;
        }
        // 2. New bitmap blocks for dirty chunks.
        let dirty: Vec<usize> = self.dirty_chunks.iter().copied().collect();
        for ch in dirty {
            let bm = self.bitmap(c, ch)?.clone();
            let old = self.ci_bitmap_addr(ch);
            let pa = self.ip_alloc()?;
            c.write_block(pa, &bm)?;
            if old != 0 {
                self.ip_pending_free.push(old);
            }
            let l = self.chunks[ch];
            let off = self.ci(ch);
            let d = &mut self.cibs[l.cib].0.data;
            wr64(d, off, xid);
            wr64(d, off + 24, pa);
            self.cibs[l.cib].0.dirty = true;
        }
        self.dirty_chunks.clear();
        // 3. Copy-on-write chunk-info blocks.
        let addr_off = rd32(&self.data, sm::DEV + sm::D_ADDR_OFFSET) as usize;
        for ci in 0..self.cibs.len() {
            if !self.cibs[ci].0.dirty {
                continue;
            }
            let pa = self.ip_alloc()?;
            let old = self.cibs[ci].0.paddr;
            self.ip_pending_free.push(old);
            let blk = &mut self.cibs[ci].0;
            ObjHeader { cksum: 0, oid: pa, xid, type_and_flags: OT_SPACEMAN_CIB | OBJ_PHYSICAL, subtype: 0 }.write(&mut blk.data);
            checksum::seal_object(&mut blk.data);
            c.write_block(pa, &blk.data)?;
            blk.paddr = pa;
            blk.dirty = false;
            match self.cibs[ci].1 {
                None => wr64(&mut self.data, addr_off + ci * 8, pa),
                Some((cab, slot)) => {
                    wr64(&mut self.cabs[cab].data, 0x28 + slot * 8, pa);
                    self.cabs[cab].dirty = true;
                }
            }
        }
        // 4. Copy-on-write CIB address blocks.
        for i in 0..self.cabs.len() {
            if !self.cabs[i].dirty {
                continue;
            }
            let pa = self.ip_alloc()?;
            let old = self.cabs[i].paddr;
            self.ip_pending_free.push(old);
            let blk = &mut self.cabs[i];
            ObjHeader { cksum: 0, oid: pa, xid, type_and_flags: OT_SPACEMAN_CAB | OBJ_PHYSICAL, subtype: 0 }.write(&mut blk.data);
            checksum::seal_object(&mut blk.data);
            c.write_block(pa, &blk.data)?;
            blk.paddr = pa;
            blk.dirty = false;
            wr64(&mut self.data, addr_off + i * 8, pa);
        }
        // 5. Release superseded internal-pool blocks and write the IP bitmap
        //    into a fresh slot of its ring.
        for pa in std::mem::take(&mut self.ip_pending_free) {
            self.ip_free_now(pa)?;
        }
        if self.ip_dirty {
            self.write_ip_bitmap(c, xid)?;
        }
        // 6. Counters.
        wr64(&mut self.data, sm::DEV + sm::D_FREE_COUNT, self.free_count);
        let _ = bs;
        Ok(())
    }

    fn write_ip_bitmap(&mut self, c: &Container, xid: u64) -> Result<()> {
        let bs = self.bs;
        let d = &self.data;
        let ring_base = rd64(d, sm::IP_BM_BASE);
        let ring_len = rd32(d, sm::IP_BM_BLOCK_COUNT) as usize;
        let size = rd32(d, sm::IP_BM_SIZE_IN_BLOCKS) as usize;
        let xid_off = rd32(d, sm::IP_BM_XID_OFFSET) as usize;
        let bm_off = rd32(d, sm::IP_BITMAP_OFFSET) as usize;
        let next_off = rd32(d, sm::IP_BM_FREE_NEXT_OFFSET) as usize;
        if next_off + ring_len * 2 > self.data.len() || bm_off + size * 2 > self.data.len() || xid_off + size * 8 > self.data.len() {
            return Err(Error::Corrupt("internal pool ring metadata out of bounds".into()));
        }
        for i in 0..size {
            let mut head = rd16(&self.data, sm::IP_BM_FREE_HEAD);
            let mut tail = rd16(&self.data, sm::IP_BM_FREE_TAIL);
            if head as usize >= ring_len || tail as usize >= ring_len || head == tail {
                return Err(Error::Corrupt("internal pool bitmap ring is exhausted or damaged".into()));
            }
            let old = rd16(&self.data, bm_off + i * 2);
            let new = head;
            let piece = &self.ip_bm[i * bs..(i + 1) * bs];
            c.write_block(ring_base + new as u64, piece)?;
            // Pop the head of the free list.
            head = rd16(&self.data, next_off + new as usize * 2);
            wr16(&mut self.data, next_off + new as usize * 2, SPACEMAN_IP_BM_INDEX_INVALID);
            // Append the superseded slot at the tail.
            wr16(&mut self.data, next_off + tail as usize * 2, old);
            wr16(&mut self.data, next_off + old as usize * 2, SPACEMAN_IP_BM_INDEX_INVALID);
            tail = old;
            wr16(&mut self.data, sm::IP_BM_FREE_HEAD, head);
            wr16(&mut self.data, sm::IP_BM_FREE_TAIL, tail);
            wr16(&mut self.data, bm_off + i * 2, new);
            wr64(&mut self.data, xid_off + i * 8, xid);
        }
        self.ip_dirty = false;
        Ok(())
    }
}

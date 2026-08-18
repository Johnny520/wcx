// art/elf.rs — ELF32/64 symbol scanner and .gnu_debugdata decompressor
//
// Locates `libart.so` in the process address space via `dl_iterate_phdr`, then
// scans its section headers for symbols.  When the on-disk `.dynsym`/`.symtab`
// do not contain the target name, the `.gnu_debugdata` section (an XZ-compressed
// mini ELF) is decompressed at runtime using `lzma_stream_buffer_decode` loaded
// from `liblzma.so` via `dlopen`/`dlsym`.

use crate::loge;
use libc::c_int;
use std::{
    ffi::{CStr, c_char, c_void},
    sync::OnceLock,
};

// ── ELF types (32/64 bit via cfg) ────────────────────────────────────────────

#[cfg(target_pointer_width = "64")]
mod elf_types {
    pub type Half = u16;
    pub type Word = u32;
    pub type Xword = u64;
    pub type Addr = u64;
    pub type Off = u64;
    pub const SHT_SYMTAB: Word = 2;
    pub const SHT_DYNSYM: Word = 11;
    #[repr(C)]
    pub struct Ehdr {
        pub e_ident: [u8; 16],
        pub e_type: Half,
        pub e_machine: Half,
        pub e_version: Word,
        pub e_entry: Addr,
        pub e_phoff: Off,
        pub e_shoff: Off,
        pub e_flags: Word,
        pub e_ehsize: Half,
        pub e_phentsize: Half,
        pub e_phnum: Half,
        pub e_shentsize: Half,
        pub e_shnum: Half,
        pub e_shstrndx: Half,
    }
    #[repr(C)]
    pub struct Shdr {
        pub sh_name: Word,
        pub sh_type: Word,
        pub sh_flags: Xword,
        pub sh_addr: Addr,
        pub sh_offset: Off,
        pub sh_size: Xword,
        pub sh_link: Word,
        pub sh_info: Word,
        pub sh_addralign: Xword,
        pub sh_entsize: Xword,
    }
    #[repr(C)]
    pub struct Sym {
        pub st_name: Word,
        pub st_info: u8,
        pub st_other: u8,
        pub st_shndx: Half,
        pub st_value: Addr,
        pub st_size: Xword,
    }
}

#[cfg(target_pointer_width = "32")]
mod elf_types {
    pub type Half = u16;
    pub type Word = u32;
    pub type Xword = u32;
    pub type Addr = u32;
    pub type Off = u32;
    pub const SHT_SYMTAB: Word = 2;
    pub const SHT_DYNSYM: Word = 11;
    #[repr(C)]
    pub struct Ehdr {
        pub e_ident: [u8; 16],
        pub e_type: Half,
        pub e_machine: Half,
        pub e_version: Word,
        pub e_entry: Addr,
        pub e_phoff: Off,
        pub e_shoff: Off,
        pub e_flags: Word,
        pub e_ehsize: Half,
        pub e_phentsize: Half,
        pub e_phnum: Half,
        pub e_shentsize: Half,
        pub e_shnum: Half,
        pub e_shstrndx: Half,
    }
    #[repr(C)]
    pub struct Shdr {
        pub sh_name: Word,
        pub sh_type: Word,
        pub sh_flags: Xword,
        pub sh_addr: Addr,
        pub sh_offset: Off,
        pub sh_size: Xword,
        pub sh_link: Word,
        pub sh_info: Word,
        pub sh_addralign: Xword,
        pub sh_entsize: Xword,
    }
    #[repr(C)]
    pub struct Sym {
        pub st_name: Word,
        pub st_info: u8,
        pub st_other: u8,
        pub st_shndx: Half,
        pub st_value: Addr,
        pub st_size: Xword,
    }
}

use elf_types::{Ehdr, SHT_DYNSYM, SHT_SYMTAB, Shdr, Sym};

// ── ElfFile ───────────────────────────────────────────────────────────────────

pub struct ElfFile {
    base: *const u8,
    size: usize,
    owned: bool, // true = munmap on drop; false = borrowed (e.g. from Vec)
}

unsafe impl Send for ElfFile {}
unsafe impl Sync for ElfFile {}

impl Drop for ElfFile {
    fn drop(&mut self) {
        if self.owned && !self.base.is_null() {
            unsafe { libc::munmap(self.base as *mut c_void, self.size) };
        }
    }
}

impl ElfFile {
    pub fn open(path: &str) -> Option<Self> {
        let cpath = std::ffi::CString::new(path).ok()?;
        let fd = unsafe { libc::open(cpath.as_ptr(), libc::O_RDONLY) };
        if fd < 0 {
            return None;
        }
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        unsafe { libc::fstat(fd, &mut st) };
        let size = st.st_size as usize;
        if size < std::mem::size_of::<Ehdr>() {
            unsafe { libc::close(fd) };
            return None;
        }
        let base = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                size,
                libc::PROT_READ,
                libc::MAP_PRIVATE,
                fd,
                0,
            )
        };
        unsafe { libc::close(fd) };
        if base == libc::MAP_FAILED {
            return None;
        }
        Some(ElfFile {
            base: base as *const u8,
            size,
            owned: true,
        })
    }

    /// Create an ElfFile view over a borrowed byte slice (no munmap on drop).
    pub fn from_slice(data: &[u8]) -> Option<Self> {
        if data.len() < std::mem::size_of::<Ehdr>() {
            return None;
        }
        Some(ElfFile {
            base: data.as_ptr(),
            size: data.len(),
            owned: false,
        })
    }

    fn ehdr(&self) -> &Ehdr {
        unsafe { &*(self.base as *const Ehdr) }
    }

    fn shdr(&self, idx: u16) -> Option<&Shdr> {
        let e = self.ehdr();
        let off = e.e_shoff as usize + idx as usize * e.e_shentsize as usize;
        if off + std::mem::size_of::<Shdr>() > self.size {
            return None;
        }
        Some(unsafe { &*(self.base.add(off) as *const Shdr) })
    }

    pub fn find_section(&self, name: &str) -> Option<(*const u8, usize)> {
        let e = self.ehdr();
        let strtab = self.shdr(e.e_shstrndx)?;
        let str_base = unsafe { self.base.add(strtab.sh_offset as usize) };
        for i in 0..e.e_shnum {
            let sh = self.shdr(i)?;
            let sh_name = unsafe {
                CStr::from_ptr(str_base.add(sh.sh_name as usize) as *const c_char)
                    .to_str()
                    .unwrap_or("")
            };
            if sh_name == name {
                let ptr = unsafe { self.base.add(sh.sh_offset as usize) };
                return Some((ptr, sh.sh_size as usize));
            }
        }
        None
    }

    fn scan_symtab(&self, sym_sh: &Shdr, str_sh: &Shdr, target: &str) -> Option<usize> {
        let sym_base = unsafe { self.base.add(sym_sh.sh_offset as usize) };
        let str_base = unsafe { self.base.add(str_sh.sh_offset as usize) };
        let count = sym_sh.sh_size as usize / std::mem::size_of::<Sym>();
        for i in 0..count {
            let sym = unsafe { &*(sym_base.add(i * std::mem::size_of::<Sym>()) as *const Sym) };
            if sym.st_value == 0 {
                continue;
            }
            let name = unsafe {
                CStr::from_ptr(str_base.add(sym.st_name as usize) as *const c_char)
                    .to_str()
                    .unwrap_or("")
            };
            if name == target || name.starts_with(target) {
                return Some(sym.st_value as usize);
            }
        }
        None
    }

    /// Scan .dynsym then .symtab for `sym_name`. Returns value (offset from base) if found.
    pub fn find_symbol(&self, sym_name: &str) -> Option<usize> {
        let e = self.ehdr();
        let mut dynsym_sh: Option<&Shdr> = None;
        let mut symtab_sh: Option<&Shdr> = None;
        let mut dynsym_str: Option<&Shdr> = None;
        let mut symtab_str: Option<&Shdr> = None;
        for i in 0..e.e_shnum {
            let sh = self.shdr(i)?;
            match sh.sh_type {
                t if t == SHT_DYNSYM => {
                    dynsym_sh = Some(sh);
                    dynsym_str = self.shdr(sh.sh_link as u16);
                }
                t if t == SHT_SYMTAB => {
                    symtab_sh = Some(sh);
                    symtab_str = self.shdr(sh.sh_link as u16);
                }
                _ => {}
            }
        }
        if let (Some(ds), Some(dss)) = (dynsym_sh, dynsym_str)
            && let Some(off) = self.scan_symtab(ds, dss, sym_name)
        {
            return Some(off);
        }
        if let (Some(ss), Some(sss)) = (symtab_sh, symtab_str)
            && let Some(off) = self.scan_symtab(ss, sss, sym_name)
        {
            return Some(off);
        }
        None
    }

    /// Prefix-matching variant of find_symbol, used by resolve_art_symbol.
    pub fn find_symbol_with_prefix(&self, sym_name: &str, prefix: bool) -> Option<usize> {
        if !prefix {
            return self.find_symbol(sym_name);
        }
        let e = self.ehdr();
        for i in 0..e.e_shnum {
            let sh = self.shdr(i)?;
            if sh.sh_type != SHT_DYNSYM && sh.sh_type != SHT_SYMTAB {
                continue;
            }
            let str_sh = self.shdr(sh.sh_link as u16)?;
            let sym_base = unsafe { self.base.add(sh.sh_offset as usize) };
            let str_base = unsafe { self.base.add(str_sh.sh_offset as usize) };
            let count = sh.sh_size as usize / std::mem::size_of::<Sym>();
            for j in 0..count {
                let sym = unsafe { &*(sym_base.add(j * std::mem::size_of::<Sym>()) as *const Sym) };
                if sym.st_value == 0 {
                    continue;
                }
                let name = unsafe {
                    CStr::from_ptr(str_base.add(sym.st_name as usize) as *const c_char)
                        .to_str()
                        .unwrap_or("")
                };
                if name.starts_with(sym_name) {
                    return Some(sym.st_value as usize);
                }
            }
        }
        None
    }
}

// ── dl_iterate_phdr ART library finder ───────────────────────────────────────

pub struct ArtLibrary {
    pub base: usize,
    pub path: String,
}

extern "C" fn phdr_callback(
    info: *mut libc::dl_phdr_info,
    _size: libc::size_t,
    data: *mut c_void,
) -> c_int {
    let result = unsafe { &mut *(data as *mut Option<ArtLibrary>) };
    let name = unsafe {
        if (*info).dlpi_name.is_null() {
            return 0;
        }
        CStr::from_ptr((*info).dlpi_name).to_str().unwrap_or("")
    };
    let base = name.rfind('/').map(|i| &name[i + 1..]).unwrap_or(name);
    if base == "libart.so" || base == "libartd.so" {
        *result = Some(ArtLibrary {
            base: unsafe { (*info).dlpi_addr as usize },
            path: name.to_owned(),
        });
        return 1; // stop
    }
    0
}

pub fn find_art_library() -> Option<ArtLibrary> {
    let mut result: Option<ArtLibrary> = None;
    unsafe { libc::dl_iterate_phdr(Some(phdr_callback), &mut result as *mut _ as *mut c_void) };
    result
}

// ── XZ decompression via runtime dlopen ──────────────────────────────────────

type LzmaDecodeFn = unsafe extern "C" fn(
    memlimit: *const u64,
    flags: u32,
    allocator: *const c_void,
    inp: *const u8,
    in_pos: *mut usize,
    in_size: usize,
    out: *mut u8,
    out_pos: *mut usize,
    out_size: usize,
) -> u32;

fn load_lzma() -> Option<LzmaDecodeFn> {
    static FN: OnceLock<Option<LzmaDecodeFn>> = OnceLock::new();
    *FN.get_or_init(|| unsafe {
        let h = libc::dlopen(c"liblzma.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if h.is_null() {
            return None;
        }
        // Intentionally leak handle so fn ptr stays valid for process lifetime
        let sym = libc::dlsym(h, c"lzma_stream_buffer_decode".as_ptr());
        if sym.is_null() {
            return None;
        }
        Some(std::mem::transmute::<*mut c_void, LzmaDecodeFn>(sym))
    })
}

pub fn decompress_xz(input: &[u8]) -> Option<Vec<u8>> {
    let decode = load_lzma()?;
    let mut buf_size = (input.len() * 4).max(65536);
    let max_size = 64 * 1024 * 1024usize;
    loop {
        let mut out = vec![0u8; buf_size];
        let memlimit = u64::MAX;
        let mut in_pos = 0usize;
        let mut out_pos = 0usize;
        const LZMA_OK: u32 = 0;
        const LZMA_BUF_ERROR: u32 = 10;
        let ret = unsafe {
            decode(
                &memlimit,
                0,
                std::ptr::null(),
                input.as_ptr(),
                &mut in_pos,
                input.len(),
                out.as_mut_ptr(),
                &mut out_pos,
                buf_size,
            )
        };
        if ret == LZMA_OK {
            out.truncate(out_pos);
            return Some(out);
        }
        if ret == LZMA_BUF_ERROR && buf_size < max_size {
            buf_size *= 2;
            continue;
        }
        loge!("Zygisk: XZ decompress error {ret}");
        return None;
    }
}

/// Find a symbol in an ELF file, falling back to .gnu_debugdata if needed.
/// Returns the symbol value (offset from ELF load base, not file base).
pub fn find_symbol_in_file(path: &str, sym_name: &str) -> Option<usize> {
    let elf = ElfFile::open(path)?;
    if let Some(off) = elf.find_symbol(sym_name) {
        return Some(off);
    }
    // Try .gnu_debugdata (XZ-compressed mini ELF)
    let (cptr, csz) = elf.find_section(".gnu_debugdata")?;
    let compressed = unsafe { std::slice::from_raw_parts(cptr, csz) };
    let decompressed = decompress_xz(compressed)?;
    // Parse the decompressed mini ELF without munmap (borrowed slice)
    let mini = ElfFile::from_slice(&decompressed)?;
    mini.find_symbol(sym_name)
}

/// Resolve an ART symbol: try dlsym(RTLD_DEFAULT), scan ELF file, then try fallback paths.
/// prefix=true enables prefix matching.
/// Returns the runtime address (loaded_base + symbol_value).
pub fn resolve_art_symbol(art_base: usize, art_path: &str, name: &str, prefix: bool) -> usize {
    // 1. Non-prefix searches try dlsym first
    if !prefix && let Ok(c) = std::ffi::CString::new(name) {
        let ptr = unsafe { libc::dlsym(libc::RTLD_DEFAULT, c.as_ptr()) };
        if !ptr.is_null() {
            return ptr as usize;
        }
    }
    // 2. Scan loaded file
    if art_base != 0
        && !art_path.is_empty()
        && let Some(elf) = ElfFile::open(art_path)
        && let Some(off) = elf.find_symbol_with_prefix(name, prefix)
    {
        return art_base + off;
    }
    // 3. Fallback paths (APEX / system)
    #[cfg(target_pointer_width = "64")]
    let fallback: &[&str] = &[
        "/apex/com.android.art/lib64/libart.so",
        "/system/lib64/libart.so",
    ];
    #[cfg(target_pointer_width = "32")]
    let fallback: &[&str] = &[
        "/apex/com.android.art/lib/libart.so",
        "/system/lib/libart.so",
    ];
    for &path in fallback {
        if path == art_path {
            continue;
        }
        let base = find_loaded_library_base(path);
        if base == 0 {
            continue;
        }
        if let Some(elf) = ElfFile::open(path)
            && let Some(off) = elf.find_symbol_with_prefix(name, prefix)
        {
            return base + off;
        }
    }
    0
}

/// Find a loaded library's base address via dl_iterate_phdr.
fn find_loaded_library_base(target: &str) -> usize {
    struct Query {
        path: *const libc::c_char,
        base: usize,
    }
    extern "C" fn cb(
        info: *mut libc::dl_phdr_info,
        _: libc::size_t,
        data: *mut libc::c_void,
    ) -> libc::c_int {
        let q = unsafe { &mut *(data as *mut Query) };
        if unsafe { (*info).dlpi_name.is_null() } {
            return 0;
        }
        if unsafe { libc::strcmp((*info).dlpi_name, q.path) } == 0 {
            unsafe {
                q.base = (*info).dlpi_addr as usize;
            }
            return 1;
        }
        0
    }
    if let Ok(c) = std::ffi::CString::new(target) {
        let mut q = Query {
            path: c.as_ptr(),
            base: 0,
        };
        unsafe {
            libc::dl_iterate_phdr(Some(cb), &mut q as *mut _ as *mut libc::c_void);
        }
        q.base
    } else {
        0
    }
}

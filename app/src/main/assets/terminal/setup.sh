set -e

source "$LOCAL/bin/utils"

info "Extracting the Ubuntu container…"

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PRIVATE_DIR"

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

ARGS="$ARGS -b $PRIVATE_DIR"
ARGS="$ARGS -b /sys"

ARGS="$ARGS -r /"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

COMMAND="(cd $LOCAL/sandbox && tar -xf $TMP_DIR/sandbox.tar.gz)"

set +e
$PROOT $ARGS /system/bin/sh -c "$COMMAND"
ret=$?
set -e

if [ "$ret" -ne 0 ]; then
    warn "PRoot extraction failed (exit code $ret), falling back to direct extraction..."

    set +e
    LD_PRELOAD="$NATIVE_LIB_DIR/liblink2symlink.so"
    export LD_PRELOAD
    /system/bin/sh -c "$COMMAND"
    unset LD_PRELOAD
    ret=$?
    set -e

    if [ "$ret" -ne 0 ]; then
        error "Extraction failed (exit code $ret)"
        exit "$ret"
    fi
fi


SANDBOX_DIR="$LOCAL/sandbox"

info "Setting up the Ubuntu container…"

nameserver="nameserver 8.8.8.8
nameserver 8.8.4.4"

hosts="127.0.0.1   localhost.localdomain localhost

# IPv6.
::1         localhost.localdomain localhost ip6-localhost ip6-loopback
fe00::0     ip6-localnet
ff00::0     ip6-mcastprefix
ff02::1     ip6-allnodes
ff02::2     ip6-allrouters
ff02::3     ip6-allhosts"

mkdir -p "$SANDBOX_DIR/etc"

printf '%s\n' "Xed-Editor" > "$SANDBOX_DIR/etc/hostname"

: > "$SANDBOX_DIR/etc/resolv.conf"
printf '%s\n' "$nameserver" > "$SANDBOX_DIR/etc/resolv.conf"

printf '%s\n' "$hosts" > "$SANDBOX_DIR/etc/hosts"

groupFile="$SANDBOX_DIR/etc/group"
aid="$(id -g)"

linesToAdd="
inet:x:3003
everybody:x:9997
android_app:x:20455
android_debug:x:50455
android_cache:x:$((10000 + aid))
android_storage:x:$((40000 + aid))
android_media:x:$((50000 + aid))
android_external_storage:x:1077
"

[ -f "$groupFile" ] || : > "$groupFile"

existing="$(cat "$groupFile")"

echo "$linesToAdd" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    gid="${line##*:}"
    case "$existing" in
        *:"$gid"*) : ;;
        *) printf '%s\n' "$line" >> "$groupFile" ;;
    esac
done


rm "$TMP_DIR"/sandbox.tar.gz
touch $LOCAL/.terminal_setup_ok_DO_NOT_REMOVE



info "Installing Node.js APT hook…"

mkdir -p "$SANDBOX_DIR/etc/apt/apt.conf.d"
mkdir -p "$SANDBOX_DIR/usr/local/bin"

cat > "$SANDBOX_DIR/etc/apt/apt.conf.d/99node-hook" << 'EOF'
DPkg::Post-Invoke {
    "if [ -x /usr/bin/node ]; then /usr/local/bin/node-postinstall.sh; fi";
};
EOF

cat > "$SANDBOX_DIR/usr/local/bin/node-postinstall.sh" << 'EOF'
#!/bin/sh
set -e

echo "[node-hook] Running Node.js post-install hook..."

JEMALLOC=""

echo "[node-hook] Searching for jemalloc..."

for path in \
    /usr/lib/*/libjemalloc.so* \
    /usr/lib/libjemalloc.so* \
    /lib/*/libjemalloc.so* \
    /lib/libjemalloc.so*; do

    if [ -e "$path" ]; then
        JEMALLOC="$path"
        echo "[node-hook] Found jemalloc: $JEMALLOC"
        break
    fi
done

if [ -z "$JEMALLOC" ]; then
    echo "[node-hook] jemalloc not installed, skipping"
    exit 0
fi

if [ ! -e /usr/bin/node ]; then
    echo "[node-hook] Node binary not found, skipping"
    exit 0
fi

if [ -e /usr/bin/node.distrib ]; then
    echo "[node-hook] Node already wrapped, skipping"
    exit 0
fi

echo "[node-hook] Verifying node binary..."

if file /usr/bin/node | grep -q ELF; then
    echo "[node-hook] Wrapping Node.js with jemalloc..."

    mv /usr/bin/node /usr/bin/node.distrib

    cat > /usr/bin/node << WRAP
#!/bin/sh
LD_PRELOAD=$JEMALLOC exec /usr/bin/node.distrib "\$@"
WRAP

    chmod +x /usr/bin/node

    echo "[node-hook] Node wrapper installed successfully"
else
    echo "[node-hook] /usr/bin/node is not an ELF binary, skipping"
fi
EOF

chmod +x "$SANDBOX_DIR/usr/local/bin/node-postinstall.sh"

info "Node.js APT hook installed"



# 检测沙箱模式：显式 TERM_MODE 优先，否则探测 root+busybox → chroot，回退 proot（结果缓存）
detect_term_mode() {
    if [ -n "$TERM_MODE" ]; then
        echo "$TERM_MODE"
        return
    fi
    if [ -f "$LOCAL/.term_mode" ]; then
        cat "$LOCAL/.term_mode"
        return
    fi
    mode="proot"
    bb=""
    for c in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox \
             /system/xbin/busybox /system/bin/busybox; do
        [ -x "$c" ] && { bb="$c"; break; }
    done
    [ -z "$bb" ] && bb="$(command -v busybox 2>/dev/null)"
    if [ -n "$bb" ]; then
        if [ "$(su -c 'id -u' 2>/dev/null)" = "0" ]; then
            mode="chroot"
        fi
    fi
    echo "$mode" > "$LOCAL/.term_mode" 2>/dev/null
    echo "$mode"
}

if [ $# -gt 0 ]; then
    sh $@
else
    clear
    MODE="$(detect_term_mode)"
    if [ "$MODE" = "chroot" ]; then
        sh $LOCAL/bin/sandbox_chroot
    else
        sh $LOCAL/bin/sandbox_proot
    fi
fi

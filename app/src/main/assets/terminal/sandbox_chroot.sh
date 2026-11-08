ROOT="$LOCAL/sandbox"
[ -d "$ROOT/tmp" ] || { mkdir -p "$ROOT/tmp"; chmod 1777 "$ROOT/tmp"; }

cat > "$ROOT/tmp/.chroot_rc" <<'RCEOF'
force_color_prompt=yes
shopt -s checkwinsize
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin
export SHELL="bash"
export PS1="\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\] \\$ "
alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'
alias pkg='apt'
alias pm='/system/bin/cmd package'
alias am='/system/bin/cmd activity'

CONTAINER_TIMEZONE="UTC"
ln -snf "/usr/share/zoneinfo/$CONTAINER_TIMEZONE" /etc/localtime
echo "$CONTAINER_TIMEZONE" > /etc/timezone

if [ ! -f /.cache/.packages_ensured_chroot ]; then
    echo 'APT::Install-Recommends "false";' > /etc/apt/apt.conf.d/99norecommends
    mkdir -p "/.cache"
    export DEBIAN_FRONTEND=noninteractive
    if apt update -y && apt install -y command-not-found sudo xkb-data libjemalloc-dev; then
        touch "/.cache/.packages_ensured_chroot"
    fi
fi

if [ -f /initrc ]; then
    . /initrc
fi

cd "${WKDIR:-/root}" || cd /root
RCEOF

cat > "$ROOT/tmp/.chroot_start" <<'STARTEOF'
root=$1
bb=""
for c in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox \
         /system/xbin/busybox /system/bin/busybox; do
    [ -x "$c" ] && { bb="$c"; break; }
done
[ -z "$bb" ] && bb="$(command -v busybox)"
[ -z "$bb" ] && { echo "chroot 模式需要 busybox（Magisk/KernelSU 自带）" >&2; exit 127; }

inner_script='
root=$1; bb=$2; home=$3
"$bb" mount -t proc proc "$root/proc" || exit 125
"$bb" mkdir -p "$root/dev" && "$bb" mount -o rbind /dev "$root/dev" || exit 125
"$bb" mkdir -p "$root/sys" 2>/dev/null && "$bb" mount -o rbind /sys "$root/sys" 2>/dev/null
"$bb" mkdir -p "$root/sdcard" 2>/dev/null && "$bb" mount -o bind /sdcard "$root/sdcard" 2>/dev/null
"$bb" mkdir -p "$root/storage" 2>/dev/null && "$bb" mount -o rbind /storage "$root/storage" 2>/dev/null
"$bb" mkdir -p "$root/data" 2>/dev/null && "$bb" mount -o rbind /data "$root/data" 2>/dev/null
for m in /apex /system /vendor /product /system_ext /odm /linkerconfig \
         /plat_property_contexts /property_contexts; do
    if [ -e "$m" ]; then
        if [ -f "$m" ]; then
            "$bb" touch "$root$m" 2>/dev/null
        else
            "$bb" mkdir -p "$root$m" 2>/dev/null
        fi
        "$bb" mount -o rbind "$m" "$root$m" 2>/dev/null
    fi
done
unset m
"$bb" mkdir -p "$root/home" "$root/root" 2>/dev/null
"$bb" mount -o bind "$home" "$root/home" 2>/dev/null
"$bb" mount -o bind "$home" "$root/root" 2>/dev/null
if [ -n "$CHROOT_CMD" ]; then
    exec "$bb" chroot "$root" /usr/bin/env -i \
        HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
        TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 WKDIR="${WKDIR:-/root}" \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin \
        /bin/bash -c "$CHROOT_CMD"
fi
exec "$bb" chroot "$root" /usr/bin/env -i \
    HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
    TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 WKDIR="${WKDIR:-/root}" \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin \
    /bin/bash --rcfile /tmp/.chroot_rc -i
'

if "$bb" unshare -m --propagation private "$bb" true 2>/dev/null; then
    "$bb" unshare -m --propagation private "$bb" sh -c "$inner_script" \
        sh "$root" "$bb" "${CHROOT_HOME:-/root}"
else
    "$bb" sh -c "$inner_script" sh "$root" "$bb" "${CHROOT_HOME:-/root}"
fi
STARTEOF

export CHROOT_CMD="$*"
export CHROOT_HOME="$EXT_HOME"
su -c "sh $ROOT/tmp/.chroot_start $ROOT"
exit $?

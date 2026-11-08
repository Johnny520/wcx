force_color_prompt=yes
shopt -s checkwinsize

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/games:/usr/local/bin:/usr/local/sbin:$LOCAL/bin:$PATH
export SHELL="bash"
export PS1="\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\] \\$ "

source "$LOCAL/bin/utils"

ln -sfn "$NATIVE_LIB_DIR/libxed_cli.so" "$LOCAL/bin/xed"

if [ -f "$LOCAL/.sandbox_degraded" ]; then
    warn "Running in degraded mode. Some features may not work. Please reinstall the terminal"
fi

CONTAINER_TIMEZONE="UTC"

ln -snf "/usr/share/zoneinfo/$CONTAINER_TIMEZONE" /etc/localtime 2>/dev/null

( echo "$CONTAINER_TIMEZONE" > /etc/timezone ) 2>/dev/null

DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive tzdata >/dev/null 2>&1


if [[ -f ~/.bashrc ]]; then
    source ~/.bashrc
fi


ensure_packages_once() {
    local marker_file="$LOCAL/.packages_ensured"
    local legacy_marker="/.cache/.packages_ensured"
    local PACKAGES=("command-not-found" "sudo" "xkb-data" "libjemalloc-dev")

    [[ -f "$marker_file" || -f "$legacy_marker" ]] && return 0

    if ! (mkdir -p "/.cache" 2>/dev/null && touch "/.cache/.proot_wtest" 2>/dev/null); then
        rm -f "/.cache/.proot_wtest" 2>/dev/null
        warn "Rootfs contains root-owned files (from chroot mode); skipping package setup."
        touch "$marker_file"
        return 0
    fi
    rm -f "/.cache/.proot_wtest" 2>/dev/null

    if ! (echo 'APT::Install-Recommends "false";' > /etc/apt/apt.conf.d/99norecommends) 2>/dev/null; then
        warn "Rootfs contains root-owned files (from chroot mode); skipping package setup."
        touch "$marker_file"
        return 0
    fi
    echo 'APT::Install-Suggests "false";' >> /etc/apt/apt.conf.d/99norecommends

    mkdir -p "/.cache"

    local MISSING=()
    for pkg in "${PACKAGES[@]}"; do
        if ! dpkg -s "$pkg" >/dev/null 2>&1; then
            MISSING+=("$pkg")
        fi
    done

    if [ ${#MISSING[@]} -eq 0 ]; then
        touch "$marker_file"
        return 0
    fi

    info "Installing missing packages: ${MISSING[*]}"

    if export DEBIAN_FRONTEND=noninteractive && \
       apt update -y && \
       apt install -y "${MISSING[@]}"; then
       touch "$marker_file"
       clear
       info "Setup complete."
    else
        error "Failed to install packages."
        return 1
    fi

    update-command-not-found 2>/dev/null || true
}


ensure_packages_once
unset -f ensure_packages_once

if [ -x /usr/lib/command-not-found -o -x /usr/share/command-not-found/command-not-found ]; then
	function command_not_found_handle {
                if [ -x /usr/lib/command-not-found ]; then
		   /usr/lib/command-not-found -- "$1"
                   return $?
                elif [ -x /usr/share/command-not-found/command-not-found ]; then
		   /usr/share/command-not-found/command-not-found -- "$1"
                   return $?
		else
		   printf "%s: command not found\n" "$1" >&2
		   return 127
		fi
	}
fi


alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'
alias pkg='apt'

if [[ -f /initrc ]]; then
    source /initrc
fi

cd "$WKDIR" || cd $HOME

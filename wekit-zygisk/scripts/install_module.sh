MODULE=$1
ROOT=$2

if [ -z "$ROOT" ]; then
    if which magisk > /dev/null; then
        echo 'Using magisk'
        ROOT=magisk
    elif which ksud > /dev/null; then
        echo 'Using ksu'
        ROOT=ksu
    elif [ -f /data/adb/apd ]; then
        echo 'Using ap'
        ROOT=ap
    else
        echo 'No root method detected'
        exit 1
    fi
else
    echo "Specified root $ROOT"
fi

case "$ROOT" in
magisk)
    magisk --install-module "$MODULE"
    ;;
ksu|kernelsu)
    ksud module install "$MODULE"
    ;;
ap|apatch)
    /data/adb/apd module install "$MODULE"
    ;;
*)
    echo "Unknown root $ROOT"
    exit 1
    ;;
esac

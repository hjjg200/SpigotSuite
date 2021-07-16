# SpigotSuite
My personal Spigot plugin suite

## Build

```shell
gradle shadowJar
```

## Update Procedure

- Change `paper.jar` symbolic link
- Change `plugins/SpigotSuite.jar`
- Change `server.properties` - `motd`
- `echo stop | nc localhost 25576`
- `sudo reboot`
- Change DNS records

## Start Command

```shell
#!/bin/bash

while read n sz u; do
    [[ $n == "MemTotal:" ]] && total=$sz && break
done < /proc/meminfo

total=$((total/1024))

if [ $total -lt 1024 ]; then
    echo 'Not enough memory to start'
    exit 1
fi

mx=$((total - 512))

# Run
java -Xms${mx}M -Xmx${mx}M \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+UseG1GC -XX:G1NewSizePercent=20 \
    -XX:G1ReservePercent=20 \
    -XX:MaxGCPauseMillis=50 \
    -XX:G1HeapRegionSize=32M \
    -jar paper.jar \
    --noconsole nogui
```

## TODO
* Check full backup memory leakage
* Backup timer and show it in info
* Backup: check size of files to backup and check disk free space

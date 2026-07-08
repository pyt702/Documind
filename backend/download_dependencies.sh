#!/bin/bash
echo "Downloading Maven dependencies to speed up future builds and startup..."
./mvnw dependency:go-offline
echo ""
echo "Dependencies downloaded successfully!"

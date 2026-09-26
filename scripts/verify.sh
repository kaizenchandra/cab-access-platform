#!/usr/bin/env sh
set -eu
./mvnw -B spotless:check verify
docker compose config --quiet
helm lint charts/cab-access
helm template cab-access charts/cab-access >/tmp/cab-access-chart.yaml
if command -v terraform >/dev/null 2>&1; then
  terraform -chdir=infra/terraform init -backend=false
  terraform -chdir=infra/terraform validate
  terraform -chdir=infra/terraform fmt -check
else
  echo 'Terraform CLI absent: use documented pinned Terraform container commands.'
fi

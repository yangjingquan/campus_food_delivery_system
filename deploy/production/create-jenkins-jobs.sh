#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on the production host." >&2
  exit 1
fi

BASE=http://127.0.0.1:18080
USER=${JENKINS_ADMIN_USER:-$(docker inspect ai-shop-jenkins --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^JENKINS_ADMIN_ID=//p' | head -n 1)}
USER=${USER:-admin}
PASS_FILE=/opt/jenkins/admin-password
test -s "$PASS_FILE"
PASS=$(cat "$PASS_FILE")
WORK=$(mktemp -d /tmp/cfd-jenkins-jobs.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
chmod 700 "$WORK"

python3 - "$WORK" <<'PY'
from pathlib import Path
import sys

root=Path(sys.argv[1])
repo="https://github.com/yangjingquan/campus_food_delivery_system.git"
jobs={
 "campus-food-gateway":"deploy/jenkins/Jenkinsfile.gateway",
 "campus-food-admin-api":"deploy/jenkins/Jenkinsfile.admin-api",
 "campus-food-user-api":"deploy/jenkins/Jenkinsfile.user-api",
 "campus-food-admin-web":"deploy/jenkins/Jenkinsfile.admin-web",
}
for name,script in jobs.items():
 xml=f'''<?xml version="1.1" encoding="UTF-8"?>
<flow-definition plugin="workflow-job">
  <description>Campus Food production deployment: {name.removeprefix('campus-food-')} only.</description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>{repo}</url><credentialsId></credentialsId></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
      <branches><hudson.plugins.git.BranchSpec><name>*/main</name></hudson.plugins.git.BranchSpec></branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions/>
    </scm>
    <scriptPath>{script}</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>
'''
 (root/f"{name}.xml").write_text(xml)
PY

curl --fail --silent --show-error --user "$USER:$PASS" "$BASE/crumbIssuer/api/json" -o "$WORK/crumb.json"
read -r CRUMB_FIELD CRUMB_VALUE < <(python3 - "$WORK/crumb.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
print(x["crumbRequestField"],x["crumb"])
PY
)

for name in campus-food-gateway campus-food-admin-api campus-food-user-api campus-food-admin-web; do
  file="$WORK/$name.xml"
  if curl --silent --output /dev/null --write-out '%{http_code}' --user "$USER:$PASS" "$BASE/job/$name/api/json" | grep -q '^200$'; then
    url="$BASE/job/$name/config.xml"
    method=POST
  else
    url="$BASE/createItem?name=$name"
    method=POST
  fi
  curl --fail --silent --show-error --user "$USER:$PASS" \
    -H "$CRUMB_FIELD: $CRUMB_VALUE" -H 'Content-Type: application/xml' \
    -X "$method" --data-binary "@$file" "$url" -o /dev/null
  echo "Configured $name"
done

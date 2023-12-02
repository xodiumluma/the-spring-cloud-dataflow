#!/usr/bin/env bash
if [ -z "$BASH_VERSION" ]; then
    echo "This script requires Bash. Use: bash $0 $*"
    exit 1
fi
SCDIR=$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")
function check_env() {
  eval ev='$'$1
  if [ "$ev" == "" ]; then
    echo "env var $1 not defined"
    if ((sourced != 0)); then
      return 1
    else
      exit 1
    fi
  fi
}
check_env NS
check_env PACKAGE_VERSION
if [ "$1" != "" ]; then
  SCDF_TYPE=$1
fi
check_env SCDF_TYPE

case $SCDF_TYPE in
"pro")
  APP_NAME=scdf-pro-app
  PACKAGE_NAME=scdf-pro.tanzu.vmware.com
  REGISTRY=dev.registry.pivotal.io
  REPO_NAME="p-scdf-for-kubernetes/scdf-pro-repo"
  ;;
"oss")
  APP_NAME=scdf-oss-app
  PACKAGE_NAME=scdf.tanzu.vmware.com
  REGISTRY=index.docker.io
  REPO_NAME="springcloud/scdf-oss-repo"
  ;;
*)
  echo "Invalid SCDF_TYPE=$SCDF_TYPE only pro or oss is acceptable"
esac
echo "Deleting $APP_NAME from $NS"
kctrl package installed delete --package-install $APP_NAME --namespace $NS --yes
kctrl package repository delete --namespace $NS --repository $PACKAGE_NAME --yes

kubectl delete packagerepositories --all  --namespace="$NS"
kubectl delete packageinstalls --all --namespace="$NS"
kubectl delete apps --all --namespace="$NS"
kubectl delete deployments --all --namespace="$NS"
kubectl delete statefulsets --all --namespace="$NS"
kubectl delete svc --all --namespace="$NS"
kubectl delete all --all --namespace="$NS"
kubectl delete pods --all --namespace="$NS"
kubectl delete pvc --all --namespace="$NS"
kubectl delete configmaps --all --namespace="$NS"
kubectl delete secrets --all --namespace="$NS"
kubectl delete namespace $NS

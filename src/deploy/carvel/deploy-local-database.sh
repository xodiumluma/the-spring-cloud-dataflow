#!/usr/bin/env bash
bold="\033[1m"
dim="\033[2m"
end="\033[0m"
if [ -z "$BASH_VERSION" ]; then
    echo "This script requires Bash. Use: bash $0 $*"
    exit 1
fi
SCDIR=$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")
start_time=$(date +%s)
if [ "$1" = "" ]; then
    echo "<database> must be provided. Choose one of postgresql or mariadb"
    exit 1
fi
case $1 in
"postgresql" | "postgres")
    DATABASE=postgresql
    ;;
"mariadb" | "maria")
    DATABASE=mariadb
    ;;
*)
    echo "Unsupported or invalid database $1"
    exit 1
    ;;
esac

K8S=$(realpath $SCDIR/../kubernetes)
if [ ! -d "$K8S" ]; then
  K8S=$(realpath $SCDIR/../../kubernetes)
fi
set +e
$SCDIR/prepare-local-namespace.sh "$DATABASE-sa" $DATABASE

kubectl create --namespace $DATABASE -f $K8S/$DATABASE/
set -e
kubectl rollout status deployment --namespace "$DATABASE" $DATABASE
set +e

FILE="$(mktemp).yml"
cat >$FILE <<EOF
apiVersion: secretgen.carvel.dev/v1alpha1
kind: SecretExport
metadata:
  name: $DATABASE
  namespace: $DATABASE
spec:
  toNamespace: '*'
EOF
echo "Create SecretExport $SECRET_NAME to $NS"
if [ "$DEBUG" = "true" ]; then
    cat $FILE
fi
kubectl apply -f $FILE

JDBC_URL="jdbc:$DATABASE://$DATABASE.$DATABASE/dataflow"
$SCDIR/configure-database.sh dataflow $DATABASE "$JDBC_URL" $DATABASE database-username database-password
$SCDIR/configure-database.sh skipper $DATABASE "$JDBC_URL" $DATABASE database-username database-password
"$SCDIR/carvel-import-secret.sh" "$DATABASE" "$NS" "$DATABASE"
export DATABASE
echo "Deployed $DATABASE. Host:$DATABASE.$DATABASE"
end_time=$(date +%s)
elapsed=$((end_time - start_time))
echo -e "Deployed $DATABASE in ${bold}$elapsed${end} seconds"

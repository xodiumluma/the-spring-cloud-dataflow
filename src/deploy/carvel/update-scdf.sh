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
# the following names are your choice.
if [ "$NS" = "" ]; then
    echo "Expected env var NS"
    exit 1
fi

if [ "$SCDF_TYPE" == "" ]; then
    echo "Environmental variable SCDF_TYPE must defined."
    exit 1
fi
if [ "$PACKAGE_VERSION" == "" ]; then
    echo "Environmental variable PACKAGE_VERSION must defined."
    exit 1
fi

case $SCDF_TYPE in
"pro")
    APP_NAME=scdf-pro-app
    ;;
"oss")
    APP_NAME=scdf-oss-app
    ;;
*)
    echo "Invalid SCDF_TYPE=$SCDF_TYPE only pro or oss is acceptable"
    ;;
esac
if [ "$1" != "" ]; then
    APP_NAME="$1"
fi
echo "Updating SCDF-$SCDF_TYPE $PACKAGE_VERSION as $APP_NAME"
if [ "$DATAFLOW_VERSION" != "" ]; then
    yq ".scdf.server.image.tag=\"$DATAFLOW_VERSION\"" -i ./scdf-values.yml
    yq ".scdf.ctr.image.tag=\"$DATAFLOW_VERSION\"" -i ./scdf-values.yml
    echo "Overriding Data Flow version=$DATAFLOW_VERSION"
fi
if [ "$SKIPPER_VERSION" != "" ]; then
    yq ".scdf.skipper.image.tag=\"$SKIPPER_VERSION\"" -i ./scdf-values.yml
    echo "Overriding Skipper version=$SKIPPER_VERSION"
fi
set +e
kctrl package installed update --package-install $APP_NAME \
    --values-file "./scdf-values.yml" \
    --version $PACKAGE_VERSION --namespace "$NS" --yes \
    --wait --wait-check-interval 10s

kctrl app status --app $APP_NAME --namespace $NS --json
kctrl package installed status --package-install $APP_NAME --namespace $NS
end_time=$(date +%s)
elapsed=$((end_time - start_time))
echo -e "Updated SCDF Package in ${bold}$elapsed${end} seconds"

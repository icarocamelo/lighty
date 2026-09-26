FROM eclipse-temurin:21-jre-alpine

WORKDIR /lighty-intent

# Create new user 'intent' with UID 1000
# https://wiki.alpinelinux.org/wiki/Setting_up_a_new_user
# https://github.com/nodejs/docker-node/blob/main/docs/BestPractices.md
RUN addgroup --system intent && adduser --system --ingroup intent --uid 1000 intent
# Move ownership of /lighty-intent folder to intent user
RUN chown -R intent:intent /lighty-intent
# Switch to intent user
USER intent

COPY LICENSE ${lighty.app.name} entrypoint.sh ./

EXPOSE 8888

ENTRYPOINT ["./entrypoint.sh", "${lighty.app.jar}"]

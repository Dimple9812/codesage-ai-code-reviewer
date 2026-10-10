# Local harness (development only)

`backend/src/test/java/harness/LocalHarness.java` starts the real backend against an embedded PostgreSQL. It adds `/harness/login`, which signs in a local user **without GitHub OAuth**. It lives in test sources and is never packaged into the application jar.

GitHub is called anonymously, so only public repositories work, within GitHub's anonymous rate limit of 60 requests/hour.

```bash
cd backend
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
mvn -q test-compile
java -cp target/test-classes:target/classes:$(cat cp.txt) harness.LocalHarness

cd ../frontend && npm run dev
# then open:
# http://localhost:8080/harness/login?login=<github-login>&githubId=1&name=Demo&repos=<owner>/<public-repo>
```

Set `AI_API_KEY` in the environment before starting to include the AI review.

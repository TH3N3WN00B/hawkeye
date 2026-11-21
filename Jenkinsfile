pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '25'))
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Verify Java 25') {
            steps {
                sh '''
                    set -eu

                    if ! command -v java >/dev/null 2>&1; then
                        echo "Hawkeye requires Java 25, but Java is not installed on this Jenkins agent." >&2
                        exit 1
                    fi

                    java_major="$(java -XshowSettings:properties -version 2>&1 \
                        | sed -n 's/^[[:space:]]*java.version = \\([0-9][0-9]*\\).*/\\1/p')"

                    if [ "$java_major" != "25" ]; then
                        echo "Hawkeye requires Java 25. The Jenkins agent reports:" >&2
                        java -version
                        exit 1
                    fi

                    java -version
                '''
            }
        }

        // Jenkins sets BUILD_NUMBER, so build.gradle.kts versions the jar year.quarter.B<build> (26.4.B25):
        // paper-plugin.yml, /he help and the startup line show it.
        // The jar is built and archived first, without tests, so it is available within seconds.
        // No `clean`: Gradle's up-to-date checks, the build cache and the configuration cache
        // (gradle.properties) keep incremental builds correct and skip work that did not change.
        // The jar is named after its version (Hawkeye-26.4.B12.jar). Every jar already in build/libs is deleted
        // first: an earlier build's Hawkeye jar, or another project's (a Jenkins job once archived another
        // plugin's jars from a reused workspace), so only this build's jar is archived; archiving fails if it is missing.
        stage('Build') {
            steps {
                sh '''
                    set -eu
                    if [ -d build/libs ]; then
                        find build/libs -maxdepth 1 -type f -name '*.jar' -print -delete
                    fi
                    ./gradlew --no-daemon --console=plain shadowJar
                '''
                archiveArtifacts artifacts: 'build/libs/Hawkeye-*.jar', fingerprint: true
            }
        }

        // Everything else `build` does: unit tests, the ArchUnit layer check and verifyPluginJar.
        // A failure here fails the run.
        stage('Test') {
            steps {
                sh './gradlew --no-daemon --console=plain build'
            }
        }
    }

    post {
        always {
            // Only Hawkeye's own reports: another project's reports may still sit in a reused workspace.
            junit allowEmptyResults: true, testResults: 'build/test-results/test/TEST-gg.moonrise.hawkeye.*.xml'
        }
    }
}

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

        // The jar is built and archived first, without tests, so it is available within seconds.
        // No `clean`: Gradle's up-to-date checks, the build cache and the configuration cache
        // (gradle.properties) keep incremental builds correct and skip work that did not change.
        stage('Build') {
            steps {
                sh './gradlew --no-daemon --console=plain shadowJar'
                archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
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
            junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'
        }
    }
}

pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        IMAGE_NAME  = 'attendance-api'
        IMAGE_TAG   = "${env.GIT_COMMIT.take(7)}"
        HEALTH_URL  = 'https://api.sokhin.site/actuator/health'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args '-v $HOME/.m2:/root/.m2'
                    reuseNode true
                }
            }
            steps {
                // Integration tests require a database and run in dedicated flows.
                // Keep CI gate fast/reliable here with unit tests only.
                sh 'mvn -B test "-Dtest=*Test,!*IntegrationTest"'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Build Jar') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args '-v $HOME/.m2:/root/.m2'
                    reuseNode true
                }
            }
            steps {
                sh 'mvn -B clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                sh """
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} -t ${IMAGE_NAME}:latest .
                """
            }
        }

        stage('Deploy') {
            steps {
                dir('devops') {
                    git branch: 'main', url: 'https://github.com/sokhin-devops/attendance_devops.git'
                }
                withCredentials([file(credentialsId: 'ansible-vault-password', variable: 'VAULT_PW_FILE')]) {
                    sh """
                        cd devops/ansible
                        ansible-playbook deploy.yml \
                            --vault-password-file "\$VAULT_PW_FILE" \
                            -e image_tag=${IMAGE_TAG}
                    """
                }
            }
        }

        stage('Health Check') {
            steps {
                sh """
                    for i in \$(seq 1 10); do
                        if curl -fsS ${HEALTH_URL} | grep -q '"status":"UP"'; then
                            echo 'API is healthy'
                            exit 0
                        fi
                        echo 'Waiting for API to come up...'
                        sleep 5
                    done
                    echo 'API failed health check' >&2
                    exit 1
                """
            }
        }
    }

    post {
        failure {
            echo 'Pipeline failed — check the stage logs above.'
        }
    }
}

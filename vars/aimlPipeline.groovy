
// vars/aimlPipeline.groovy
def call(Map config = [:]) {
    // config keys:
    // REPO (required), SERVICE_NAME (required), GIT_CREDENTIALS (default: "git-access"),
    // PYTHON_BIN (optional), VENV_DIR (optional)

    pipeline {
        agent any

        environment {
            REPO = config.REPO ?: ""
            SERVICE_NAME = config.SERVICE_NAME ?: ""
            GIT_CREDENTIALS = config.GIT_CREDENTIALS ?: "git-access"
            PYTHON_BIN = config.PYTHON_BIN ?: "/usr/bin/python3.11"
            VENV_DIR = config.VENV_DIR ?: "${WORKSPACE}/myenv"
        }

        stages {
            stage('Validate Input') {
                steps {
                    script {
                        if (!REPO?.trim() || !SERVICE_NAME?.trim()) {
                            error "Missing required parameters: REPO and SERVICE_NAME must be provided."
                        }
                        echo "Running pipeline for service: ${SERVICE_NAME}"
                    }
                }
            }

            stage('Checkout Repository') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        checkout([$class: 'GitSCM',
                            branches: [[name: '*/master']],
                            doGenerateSubmoduleConfigurations: false,
                            userRemoteConfigs: [[url: "${REPO}", credentialsId: "${GIT_CREDENTIALS}"]]
                        ])
                    }
                }
            }

            stage('Setup Python Environment') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        sh '''#!/bin/bash
                        set -e
                        echo "🐍 Creating/activating venv at ${VENV_DIR}"
                        if [ ! -d "${VENV_DIR}" ]; then
                            ${PYTHON_BIN} -m venv ${VENV_DIR}
                        fi
                        source ${VENV_DIR}/bin/activate
                        pip install --upgrade pip
                        if [ -f requirements.txt ]; then
                            pip install -r requirements.txt
                        else
                            echo "⚠️ requirements.txt not found — skipping pip install -r requirements.txt"
                        fi
                        '''
                    }
                }
            }

            stage('Service-specific setup') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        script {
                            if (SERVICE_NAME == "AIML-testcase") {
                                sh '''#!/bin/bash
                                set -e
                                source ${VENV_DIR}/bin/activate
                                echo "📦 Installing spaCy model for AIML-testcase"
                                python -m spacy download en_core_web_md
                                '''
                            } else {
                                echo "No service-specific setup required for ${SERVICE_NAME}"
                            }
                        }
                    }
                }
            }

            stage('Run Microservice (background)') {
                steps {
                    dir("${WORKSPACE}/${SERVICE_NAME}") {
                        script {
                            def runCmd = ""
                            if (SERVICE_NAME == "API-Gateway-AIML-Microservice") {
                                runCmd = "${VENV_DIR}/bin/python gateway.py > api_gateway.logs 2>&1 &"
                            } else if (SERVICE_NAME == "AIML-testcase") {
                                runCmd = "${VENV_DIR}/bin/python Integration.py > aiml-testcases.logs 2>&1 &"
                            } else if (SERVICE_NAME == "jobtestcase") {
                                runCmd = "${VENV_DIR}/bin/python integration.py > jobtestcases.logs 2>&1 &"
                            } else if (SERVICE_NAME == "Feed-AIML-Microservice") {
                                runCmd = "${VENV_DIR}/bin/python app_main.py > Feed-AIML.logs 2>&1 &"
                            } else {
                                error "Unknown SERVICE_NAME: ${SERVICE_NAME}"
                            }

                            sh """#!/bin/bash
                            set -e
                            source ${VENV_DIR}/bin/activate
                            nohup ${runCmd} || true
                            sleep 3
                            echo "=== Last 20 lines of logs (if created) ==="
                            ls -1 *.logs || true
                            tail -n 20 *.logs || true
                            """
                        }
                    }
                }
            }
        } // stages

        post {
            always {
                echo "Cleaning workspace..."
                cleanWs()
            }
        }
    } // pipeline
}

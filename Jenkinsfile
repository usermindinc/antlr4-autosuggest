#!/usr/bin/env groovy

properties([pipelineTriggers([])])

node {

    try {

        stage('checkout') {
            deleteDir()
            checkout scm
            changeLogMessage = util.changeLogs()
        }

        stage('Configure environment') {
            build_config = util.loadJenkinsConfiguration("jenkins.yaml")
            util.useJDKVersion(build_config.javaVersion)
            util.useMavenVersion(build_config.mavenVersion)
            pom = readMavenPom file: 'pom.xml'

            // For you/your team to do: Choose a slack channel. For example, Skylab has aß slack channel just for builds. If you just want the messages
            // to go to the author of the latest git commit, leave this as is (and delete the if block).
            // Remember that you need '@' (for direct messages) or '#' (for channels) on the front of the slackMessageDestination value.
            slackMessageDestination = "@${util.committerSlackName()}"
            // More complex example:
            if(util.isPullRequest() || env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master') {
                // Change out for the appropriate team channel
                slackTeamMessageDestination = "#integration-build"
            }
            gitCommit = util.commitSha()
        }

        stage('build') {
            // Let people know a build has begun
            if(env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master') {
                // Ensure that the application name is appropriate may need to include -application after artifactid
                if(slackMessageDestination != "@Jenkins") {
                    util.sendSlackMessage(slackMessageDestination, ":jenkins: ${pom.artifactId} ${pom.version} build started: <${env.BUILD_URL}|${env.JOB_NAME}#${env.BUILD_NUMBER}> \n ${changeLogMessage}")
                }
                // Ensure that the application name is appropriate may need to include -application after artifactid
                util.sendSlackMessage(slackTeamMessageDestination, ":jenkins: ${pom.artifactId} ${pom.version} build started: <${env.BUILD_URL}|${env.JOB_NAME}#${env.BUILD_NUMBER}> \n ${changeLogMessage}")
                // Add test related commands ass appropriate eg -Dbasepom.test.timeout=0 -Dbasepom.failsafe.timeout=0
                sh 'mvn -B clean deploy'
            } else {
                // Ensure that the application name is appropriate may need to include -application after artifactid
                if(slackMessageDestination != "@Jenkins") {
                    util.sendSlackMessage(slackMessageDestination, ":jenkins: ${pom.artifactId} ${pom.version} build started: <${env.BUILD_URL}|${env.JOB_NAME}#${env.BUILD_NUMBER}> \n ${changeLogMessage}")
                }
                // Add test related commands ass appropriate eg -Dbasepom.test.timeout=0 -Dbasepom.failsafe.timeout=0
                sh 'mvn -B clean package'
            }
        }

        //Scan with SourceClear to identify vulnerabilities
        stage('SourceClear scan') {
            withCredentials([string(credentialsId: 'SRCCLR_API_TOKEN', variable: 'SRCCLR_API_TOKEN')]) {
                sh "curl -sSL https://download.sourceclear.com/ci.sh | sh"
            }
        }

        //If this is a pull request - then stop here. Failsafe to keep from going though the deployment steps on PRs.
        if( util.isPullRequest() ) {
            if(slackMessageDestination != "@Jenkins") {
                util.sendSlackMessage(slackMessageDestination, ":jenkins: ${pom.artifactId}-application ${pom.version} build FAILED: ${env.BUILD_URL}consoleFull")
            }
            currentBuild.result = 'SUCCESS'
            return
        }

        stage('Write to Slack') {
            if (env.BRANCH_NAME == 'master') {
                //See if the deployment succeeded, and notify if not
                slackTeamMessageDestination = '#staging'
                slackMessage = ":jenkins_general: :live: Deployment of ${pom.artifactId} ${pom.version} Jar File to Staging NIFI cluster is succeeded!"
                if (currentBuild.result == 'FAILURE') {
                    slackMessage = ":jenkins_general_rage: Deployment of ${pom.artifactId} ${pom.version} FAILED! Error is here: ${env.BUILD_URL}" + "consoleFull"
                }
                //Send the message to an environment related room.
                util.sendSlackMessage(build_config.deploymentSlackRoom, slackMessage)
                //Send the message to the person who created the PR.
                if(slackMessageDestination != "@Jenkins") {
                 util.sendSlackMessage(slackMessageDestination, slackMessage)
                }
            }
        }
    }

    catch (buildError) {
        currentBuild.result = 'FAILURE'
        if(slackMessageDestination != "@Jenkins") {
            util.sendSlackMessage(slackMessageDestination, ":jenkins_rage: ${pom.artifactId} ${pom.version} build FAILED: ${env.BUILD_URL}consoleFull", "danger")
            util.sendFailureEmail(util.commitAuthorEmail())
        }
        throw buildError
    }

}

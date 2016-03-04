/*
 * Copyright 2015 Steve Jones. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.github.sjones4.scale

import com.amazonaws.ClientConfiguration
import com.amazonaws.Request
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.handlers.AbstractRequestHandler
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.services.identitymanagement.model.CreateAccessKeyRequest
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest
import com.amazonaws.services.identitymanagement.model.DeleteRolePolicyRequest
import com.amazonaws.services.identitymanagement.model.DeleteRoleRequest
import com.amazonaws.services.identitymanagement.model.PutRolePolicyRequest
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest
import com.github.sjones4.youcan.youare.YouAreClient
import com.github.sjones4.youcan.youare.model.CreateAccountRequest
import com.github.sjones4.youcan.youare.model.DeleteAccountRequest
import com.github.sjones4.youcan.youtoken.YouTokenClient
import org.junit.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.Assert.assertNotNull

/**
 * Scale test for STS assume role.
 *
 * https://eucalyptus.atlassian.net/browse/EUCA-11136
 */
class ScaleSTSAssumeRoleTest {

  private final AWSCredentialsProvider eucalyptusCredentials

  ScaleSTSAssumeRoleTest( ) {
    this.eucalyptusCredentials = new StaticCredentialsProvider( new BasicAWSCredentials(
        Objects.toString( System.getenv('AWS_ACCESS_KEY_ID'),     System.getenv('AWS_ACCESS_KEY') ),
        Objects.toString( System.getenv('AWS_SECRET_ACCESS_KEY'), System.getenv('AWS_SECRET_KEY') )
    ) )
  }

  private String cloudUri( String env, String servicePath ) {
    String url = System.getenv( env )
    assertNotNull( "Expected URL from environment (${env})", url )
    URI.create( url )
        .resolve( servicePath )
        .toString()
  }

  private YouAreClient getYouAreClient( final AWSCredentialsProvider credentials = eucalyptusCredentials ) {
    final YouAreClient euare = new YouAreClient( credentials, new ClientConfiguration(
        socketTimeout: TimeUnit.MINUTES.toMillis( 2 )
    ) )
    euare.setEndpoint( cloudUri( 'AWS_IAM_URL', '/services/Euare' ) )
    euare
  }

  private YouTokenClient getYouTokenClient( final AWSCredentialsProvider credentials = eucalyptusCredentials ) {
    final YouTokenClient tokens = new YouTokenClient( credentials, new ClientConfiguration(
        socketTimeout: TimeUnit.MINUTES.toMillis( 2 )
    ) )
    tokens.setEndpoint( cloudUri( 'TOKEN_URL', '/services/Tokens' ) )
    tokens
  }

  private void print( String text ) {
    System.out.println( text )
  }

  @Test
  void test( ) {
    final String namePrefix = UUID.randomUUID().toString().substring(0, 13) + "-";
    print( "Using resource prefix for test: " + namePrefix );

    final long startTime = System.currentTimeMillis( )
    final List<Runnable> cleanupTasks = [] as List<Runnable>
    try {
      final int tokens = 10000
      final int threads = 50
      final int iterations = tokens / threads
      final String roleAccountName = "${namePrefix}role-account"
      final String clientAccountName = "${namePrefix}client-account"
      final Map<String,String> aliasToIdMap = [:]
      getYouAreClient( ).with {
        [ roleAccountName, clientAccountName ].each { String accountName ->
          print("Creating test account ${accountName}")
          createAccount(new CreateAccountRequest(accountName: accountName)).with {
            aliasToIdMap.put( accountName, account.accountId )
          }
          cleanupTasks.add {
            print("Deleting account: ${accountName}")
            deleteAccount(new DeleteAccountRequest(accountName: accountName, recursive: true))
          }
        }
      }

      // Get credentials for role account
      AWSCredentialsProvider roleAccountCredentials = getYouAreClient().with {
        addRequestHandler(new AbstractRequestHandler() {
          public void beforeRequest(final Request<?> request) {
            request.addParameter("DelegateAccount", roleAccountName)
          }
        })
        createAccessKey(new CreateAccessKeyRequest(userName: "admin")).with {
          accessKey?.with {
            new StaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey))
          }
        }
      }
      assertNotNull("Expected role account credentials", roleAccountCredentials)

      // Get credentials for client account
      AWSCredentialsProvider clientAccountCredentials = getYouAreClient().with {
        addRequestHandler(new AbstractRequestHandler() {
          public void beforeRequest(final Request<?> request) {
            request.addParameter("DelegateAccount", clientAccountName)
          }
        })
        createAccessKey(new CreateAccessKeyRequest(userName: "admin")).with {
          accessKey?.with {
            new StaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey))
          }
        }
      }
      assertNotNull("Expected client account credentials", clientAccountCredentials)

      final String roleName = namePrefix + "role";
      print( "Creating role ${roleName}" )
      final String roleArn = null
      getYouAreClient( roleAccountCredentials ).with {
        createRole( new CreateRoleRequest(
            roleName: roleName,
            assumeRolePolicyDocument: """\
              {
                "Statement": [ {
                  "Effect": "Allow",
                  "Principal": {
                    "AWS": [ "arn:aws:iam::${aliasToIdMap.get(clientAccountName)}:root" ]
                  },
                  "Action": [ "sts:AssumeRole" ]
                } ]
              }
            """.stripIndent( )
        ) ).with {
          roleArn = role.arn
        }
        cleanupTasks.add{
          print( "Deleting role: " + roleName );
          deleteRole( new DeleteRoleRequest( roleName: roleName ) )
        }
        print( "Creating policy for role ${roleName}" )
        putRolePolicy( new PutRolePolicyRequest(
            roleName: roleName,
            policyName: 'role-policy-1',
            policyDocument: '''\
              {
                "Statement": [
                  {
                    "Action": [
                      "ec2:*"
                    ],
                    "Effect": "Allow",
                    "Resource": "*"
                  },
                  {
                    "Action": [
                      "ec2:RunInstances"
                    ],
                    "Effect": "Deny",
                    "Resource": "*",
                    "Condition": {
                      "StringEqualsIgnoreCase": {
                        "ec2:InstanceType": "t1.micro"
                      }
                    }
                  }
                ]
              }
            '''.stripIndent( )
        ) )
        cleanupTasks.add{
          print( "Deleting role policy: " + roleName )
          deleteRolePolicy( new DeleteRolePolicyRequest(
              roleName: roleName,
              policyName: 'role-policy-1'
          ) )
        }
      }
      assertNotNull("Expected role ARN", roleArn)

      print( "Requesting ${tokens} tokens using ${threads} threads" )
      final CountDownLatch latch = new CountDownLatch( threads )
      ( 1..threads ).each { Integer thread ->
        Thread.start {
          try {
            getYouTokenClient( clientAccountCredentials ).with {
              ( 1..iterations ).each{ Integer token ->
                assumeRole( new AssumeRoleRequest(
                    roleArn: roleArn,
                    roleSessionName: "role-session-${thread}-${token}"
                ) )
                if ( token % 100 == 0 ) {
                  println("[${thread}] Requested ${token} tokens")
                }
              }
            }
          } finally {
            latch.countDown( )
          }
        }
      }
      latch.await( )

      print( "Test complete in ${System.currentTimeMillis()-startTime}ms" )
    } finally {
      // Attempt to clean up anything we created
      print( "Running cleanup tasks" )
      final long cleanupStart = System.currentTimeMillis( )
      cleanupTasks.reverseEach { Runnable cleanupTask ->
        try {
          cleanupTask.run()
        } catch ( Exception e ) {
          e.printStackTrace( )
        }
      }
      print( "Completed cleanup tasks in ${System.currentTimeMillis()-cleanupStart}ms" )
    }
  }
}

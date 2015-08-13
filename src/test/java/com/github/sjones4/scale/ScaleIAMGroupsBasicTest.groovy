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

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.services.identitymanagement.model.CreateGroupRequest
import com.amazonaws.services.identitymanagement.model.DeleteGroupRequest
import com.amazonaws.services.identitymanagement.model.ListGroupsResult
import com.github.sjones4.youcan.youare.YouAreClient
import org.junit.Test

import java.util.concurrent.CountDownLatch

import static org.junit.Assert.assertNotNull

/**
 * Scale test for IAM groups in a single account.
 */
class ScaleIAMGroupsBasicTest {

  private final AWSCredentialsProvider eucalyptusCredentials

  ScaleIAMGroupsBasicTest( ) {
    this.eucalyptusCredentials = new StaticCredentialsProvider( new BasicAWSCredentials(
        System.getenv('AWS_ACCESS_KEY'),
        System.getenv('AWS_SECRET_KEY')
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
    final YouAreClient euare = new YouAreClient( credentials )
    euare.setEndpoint( cloudUri( 'AWS_IAM_URL', '/services/Euare' ) )
    euare
  }

  private void print( String text ) {
    System.out.println( text )
  }

  @Test
  void test( ) {
    final String namePrefix = UUID.randomUUID().toString().substring(0, 13) + "-";
    print( "Using resource prefix for test: " + namePrefix );

    final long startTime = System.currentTimeMillis( )
    final List<List<Runnable>> allCleanupTasks = new ArrayList<>( )
    try {
      final int threads = 20
      final int groups = 50000
      final int iterations = groups / threads
      print( "Creating ${groups} groups using ${threads} threads" )
      final CountDownLatch latch = new CountDownLatch( threads )
      ( 1..threads ).each { Integer thread ->
        final List<Runnable> cleanupTasks = [] as List<Runnable>
        allCleanupTasks << cleanupTasks
        Thread.start {
          try {
            getYouAreClient( ).with {
              ( 1..iterations ).each{ Integer group ->
                final String groupName = "${namePrefix}group-${thread}-${group}"
                createGroup(new CreateGroupRequest(groupName: groupName))
                cleanupTasks.add {
                  deleteGroup(new DeleteGroupRequest(groupName: groupName))
                }
                if (group % 100 == 0) {
                  println("[${thread}] Created ${group} groups")
                }
              }
            }
          } finally {
            latch.countDown( )
          }
        }
      }
      latch.await( )

      print( "Created ${groups} group, describing." )
      long before = System.currentTimeMillis( )
      getYouAreClient( ).with {
        listGroups( ).with { ListGroupsResult result ->
          print( "Described ${result.groups.size()} groups" )
        }
      }
      print( "Described groups in ${System.currentTimeMillis()-before}ms" )

      print( "Test complete in ${System.currentTimeMillis()-startTime}ms" )
    } finally {
      // Attempt to clean up anything we created
      print( "Running cleanup tasks" )
      final long cleanupStart = System.currentTimeMillis( )
      final CountDownLatch cleanupLatch = new CountDownLatch( allCleanupTasks.size( ) )
      allCleanupTasks.each { List<Runnable> cleanupTasks ->
        Thread.start {
          try {
            cleanupTasks.reverseEach { Runnable cleanupTask ->
              try {
                cleanupTask.run()
              } catch ( Exception e ) {
                e.printStackTrace( )
              }
            }
          } finally {
            cleanupLatch.countDown( )
          }
        }
      }
      cleanupLatch.await( )
      print( "Completed cleanup tasks in ${System.currentTimeMillis()-cleanupStart}ms" )
    }
  }
}

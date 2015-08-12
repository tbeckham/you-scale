<!--
 Copyright 2015 Steve Jones. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License").
 You may not use this file except in compliance with the License.
 A copy of the License is located at

  http://aws.amazon.com/apache2.0

 or in the "license" file accompanying this file. This file is distributed
 on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 express or implied. See the License for the specific language governing
 permissions and limitations under the License.
-->

Eucalyptus scale tests
======================
Basic scale test scripts to help evaluation of performance for X resources in Y accounts.

To run a test use (e.g.):

    gradlew -Dtest.single=ScaleASLaunchConfigurationsAccounts test

Tests expect environment variables to be defined for credentials and endpoints, as provided by a eucarc file.

To generated an IDEA project:

    gradlew idea

To build run:

    gradlew testClasses

Running a full build will run all the tests, so don't do that ...


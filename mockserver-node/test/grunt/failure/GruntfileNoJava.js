/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

// Run by mock_server_failure_test.js with a PATH that holds no java.
module.exports = function (grunt) {

    grunt.initConfig({
        start_mockserver: {
            options: {
                serverPort: process.env.MOCKSERVER_TEST_PORT,
                // any existing file stands for the jar: nothing reads it
                jarPath: __filename
            }
        }
    });

    // load this plugin's task
    grunt.loadTasks('../../../tasks');

};

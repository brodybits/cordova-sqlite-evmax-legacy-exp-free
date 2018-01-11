/* 'use strict'; */

var MYTIMEOUT = 12000;

var DEFAULT_SIZE = 5000000; // max to avoid popup in safari/ios

// FUTURE TODO replace in test(s):
function ok(test, desc) { expect(test).toBe(true); }

// XXX TODO REFACTOR OUT OF OLD TESTS:
var wait = 0;
var test_it_done = null;
function xtest_it(desc, fun) { xit(desc, fun); }
function test_it(desc, fun) {
  wait = 0;
  it(desc, function(done) {
    test_it_done = done;
    fun();
  }, MYTIMEOUT);
}
function stop(n) {
  if (!!n) wait += n
  else ++wait;
}
function start(n) {
  if (!!n) wait -= n;
  else --wait;
  if (wait == 0) test_it_done();
}

var isWindows = /Windows /.test(navigator.userAgent);
var isAndroid = !isWindows && /Android/.test(navigator.userAgent);

// NOTE: While in certain version branches there is no difference between
// the default Android implementation and implementation #2,
// this test script will also apply the androidLockWorkaround: 1 option
// in case of implementation #2.
var pluginScenarioList = [
  isAndroid ? 'Plugin-implementation-default' : 'Plugin',
  'Plugin-implementation-2'
];

// XXX TBD:
// var pluginScenarioCount = isAndroid ? 2 : 1;
var pluginScenarioCount = 1;

var mytests = function() {

  for (var i=0; i<pluginScenarioCount; ++i) {

    describe(pluginScenarioList[i] + ': parallel reader test(s)', function() {
      var scenarioName = scenarioList[i];
      var suiteName = scenarioName + ': ';
      var isWebSql = (i === 1);
      var isImpl2 = (i === 2);

        test_it(suiteName + ' same database file with parallel read-only handles', function () {
          var dbname = 'parallel-reader-test.db';
          // prevent reuse of database from default db implementation:
          var myname = (isImpl2 ? 'i2-' : '') + dbname;

          var mydb = window.sqlitePlugin.openDatabase({name: myname, location: 'default'});
          var dbr1 = window.sqlitePlugin.openDatabase({name: myname, location: 'default', isReadOnly: 'yes'});
          var dbr2 = window.sqlitePlugin.openDatabase({name: myname, location: 'default', isReadOnly: 'yes'});

          stop(1);

          mydb.transaction(function (tx) {
            tx.executeSql('DROP TABLE IF EXISTS tt');
            tx.executeSql('CREATE TABLE IF NOT EXISTS tt (test_data)');
          }, function(error) {
            console.log("ERROR: " + error.message);
            ok(false, error.message);
            start(1);
          }, function() {
            dbr1.executeSql('SELECT COUNT(*) AS recordCount FROM tt', [], function (rs1) {
              // expect(rs1.rows.item(0)).toEqual({}); // VISUAL CHECK
              expect(rs1.rows.item(0).recordCount).toBe(0);
              mydb.transaction(function (tx) {
                tx.executeSql('INSERT INTO tt VALUES (?)', ['My-test-data']);
              }, function(error) {
                console.log("ERROR: " + error.message);
                ok(false, error.message);
                start(1);
              }, function() {
                dbr2.executeSql('SELECT test_data from tt', [], function (rs2) {
                  // expect(rs2.rows.item(0)).toEqual({}); // VISUAL CHECK
                  expect(rs2.rows.item(0).test_data).toBe('My-test-data');
                  start(1);
                }, function(error) {
                  console.log("ERROR: " + error.message);
                  ok(false, error.message);
                  start(1);
                });
              });
            }, function(error) {
              console.log("ERROR: " + error.message);
              ok(false, error.message);
              start(1);
            });
          });
        });

    });
  }

}

if (window.hasBrowser) mytests();
else exports.defineAutoTests = mytests;

/* vim: set expandtab : */

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.livy;

import org.apache.commons.io.IOUtils;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterGroup;
import org.apache.zeppelin.interpreter.InterpreterOutput;
import org.apache.zeppelin.interpreter.InterpreterOutputListener;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResultMessageOutput;
import org.apache.zeppelin.interpreter.LazyOpenInterpreter;
import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;
import org.apache.zeppelin.user.AuthenticationInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class LivyInterpreterIT extends WithLivyServer {
  private static final Logger LOGGER = LoggerFactory.getLogger(LivyInterpreterIT.class);
  private static Properties properties;

  @BeforeAll
  public static void beforeAll() throws IOException {
    if (!checkPreCondition()) {
      return;
    }
    WithLivyServer.beforeAll();
    properties = new Properties();
    properties.setProperty("zeppelin.livy.url", LIVY_ENDPOINT);
    properties.setProperty("zeppelin.livy.session.create_timeout", "120");
    properties.setProperty("zeppelin.livy.spark.sql.maxResult", "100");
    properties.setProperty("zeppelin.livy.displayAppInfo", "false");
  }


  @Test
  void testSparkInterpreter() throws InterpreterException {
    if (!checkPreCondition()) {
      return;
    }
    InterpreterGroup interpreterGroup = new InterpreterGroup("group_1");
    interpreterGroup.put("session_1", new ArrayList<Interpreter>());
    LivySparkInterpreter sparkInterpreter = new LivySparkInterpreter(properties);
    sparkInterpreter.setInterpreterGroup(interpreterGroup);
    interpreterGroup.get("session_1").add(sparkInterpreter);
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    InterpreterContext context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setAuthenticationInfo(authInfo)
        .setInterpreterOut(output)
        .build();
    sparkInterpreter.open();

    LivySparkSQLInterpreter sqlInterpreter = new LivySparkSQLInterpreter(properties);
    interpreterGroup.get("session_1").add(sqlInterpreter);
    sqlInterpreter.setInterpreterGroup(interpreterGroup);
    sqlInterpreter.open();

    try {
      // detect spark version
      InterpreterResult result = sparkInterpreter.interpret("sc.version", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());

      boolean isSpark2 = isSpark2(sparkInterpreter, context);
      testRDD(sparkInterpreter, isSpark2);
      testDataFrame(sparkInterpreter, sqlInterpreter, isSpark2);

    } finally {
      sparkInterpreter.close();
      sqlInterpreter.close();
    }
  }

  private void testRDD(final LivySparkInterpreter sparkInterpreter, boolean isSpark2) {
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    final InterpreterContext context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setAuthenticationInfo(authInfo)
        .setInterpreterOut(output)
        .build();

    InterpreterResult result = sparkInterpreter.interpret("sc.parallelize(1 to 10).sum()", context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());
    assertTrue(result.message().get(0).getData().contains("Double = 55.0"));

    // single line comment
    String singleLineComment = "println(1)// my comment";
    result = sparkInterpreter.interpret(singleLineComment, context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());

    // multiple line comment
    String multipleLineComment = "println(1)/* multiple \n" + "line \n" + "comment */";
    result = sparkInterpreter.interpret(multipleLineComment, context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());

    // multi-line string
    String multiLineString = "val str = \"\"\"multiple\n" +
        "line\"\"\"\n" +
        "println(str)";
    result = sparkInterpreter.interpret(multiLineString, context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());
    assertTrue(result.message().get(0).getData().contains("multiple\nline"));

    // case class
    String caseClassCode = "case class Person(id:Int, \n" +
        "name:String)\n" +
        "val p=Person(1, \"name_a\")";
    result = sparkInterpreter.interpret(caseClassCode, context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());
    assertTrue(result.message().get(0).getData().contains("p: Person = Person(1,name_a)"));

    // object class
    String objectClassCode = "object Person {}";
    result = sparkInterpreter.interpret(objectClassCode, context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());
    if (!isSpark2) {
      assertTrue(result.message().get(0).getData().contains("defined module Person"));
    } else {
      assertTrue(result.message().get(0).getData().contains("defined object Person"));
    }

    // html output
    String htmlCode = "println(\"%html <h1> hello </h1>\")";
    result = sparkInterpreter.interpret(htmlCode, context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(1, result.message().size());
    assertEquals(InterpreterResult.Type.HTML, result.message().get(0).getType());

    // error
    result = sparkInterpreter.interpret("println(a)", context);
    assertEquals(InterpreterResult.Code.ERROR, result.code());
    assertEquals(InterpreterResult.Type.TEXT, result.message().get(0).getType());
    assertTrue(result.message().get(0).getData().contains("error: not found: value a"));

    // incomplete code
    result = sparkInterpreter.interpret("if(true){", context);
    assertEquals(InterpreterResult.Code.ERROR, result.code());
    assertEquals(InterpreterResult.Type.TEXT, result.message().get(0).getType());
    assertTrue(result.message().get(0).getData().contains("incomplete statement"));

    // cancel
    if (sparkInterpreter.livyVersion.newerThanEquals(LivyVersion.LIVY_0_3_0)) {
      Thread cancelThread = new Thread() {
        @Override
        public void run() {
          // invoke cancel after 1 millisecond to wait job starting
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          sparkInterpreter.cancel(context);
        }
      };
      cancelThread.start();
      result = sparkInterpreter
          .interpret("sc.parallelize(1 to 10).foreach(e=>Thread.sleep(10*1000))", context);
      assertEquals(InterpreterResult.Code.ERROR, result.code());
      String message = result.message().get(0).getData();
      // 2 possibilities, sometimes livy doesn't return the real cancel exception
      assertTrue(message.contains("cancelled part of cancelled job group") ||
          message.contains("Job is cancelled"));
    }
  }

  private void testDataFrame(LivySparkInterpreter sparkInterpreter,
                             final LivySparkSQLInterpreter sqlInterpreter,
                             boolean isSpark2) throws LivyException {
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    final InterpreterContext context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setAuthenticationInfo(authInfo)
        .setInterpreterOut(output)
        .build();

    InterpreterResult result = null;
    // test DataFrame api
    if (!isSpark2) {
      result = sparkInterpreter.interpret(
          "val df=sqlContext.createDataFrame(Seq((\"hello\",20))).toDF(\"col_1\", \"col_2\")\n"
              + "df.collect()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertTrue(result.message().get(0).getData()
          .contains("Array[org.apache.spark.sql.Row] = Array([hello,20])"));
    } else {
      result = sparkInterpreter.interpret(
          "val df=spark.createDataFrame(Seq((\"hello\",20))).toDF(\"col_1\", \"col_2\")\n"
              + "df.collect()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertTrue(result.message().get(0).getData()
          .contains("Array[org.apache.spark.sql.Row] = Array([hello,20])"));
    }
    sparkInterpreter.interpret("df.registerTempTable(\"df\")", context);
    // test LivySparkSQLInterpreter which share the same SparkContext with LivySparkInterpreter
    result = sqlInterpreter.interpret("select * from df where col_1='hello'", context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
    assertEquals("col_1\tcol_2\nhello\t20", result.message().get(0).getData());
    // double quotes
    result = sqlInterpreter.interpret("select * from df where col_1=\"hello\"", context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
    assertEquals("col_1\tcol_2\nhello\t20", result.message().get(0).getData());

    // only enable this test in spark2 as spark1 doesn't work for this case
    if (isSpark2) {
      result = sqlInterpreter.interpret("select * from df where col_1=\"he\\\"llo\" ", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
    }

    // single quotes inside attribute value
    result = sqlInterpreter.interpret("select * from df where col_1=\"he'llo\"", context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());

    // test sql with syntax error
    result = sqlInterpreter.interpret("select * from df2", context);
    assertEquals(InterpreterResult.Code.ERROR, result.code());
    assertEquals(InterpreterResult.Type.TEXT, result.message().get(0).getType());

    String errMsg = result.message().get(0).getData();
    assertTrue(errMsg.contains("Table not found") ||
        errMsg.contains("Table or view not found") ||
        errMsg.contains("TABLE_OR_VIEW_NOT_FOUND"));

    // test sql cancel
    if (sqlInterpreter.getLivyVersion().newerThanEquals(LivyVersion.LIVY_0_3_0)) {
      Thread cancelThread = new Thread() {
        @Override
        public void run() {
          sqlInterpreter.cancel(context);
        }
      };
      cancelThread.start();
      //sleep so that cancelThread performs a cancel.
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      result = sqlInterpreter
          .interpret("select count(1) from df", context);
      if (result.code().equals(InterpreterResult.Code.ERROR)) {
        String message = result.message().get(0).getData();
        // 2 possibilities, sometimes livy doesn't return the real cancel exception
        assertTrue(message.contains("cancelled part of cancelled job group") ||
            message.contains("Job is cancelled"));
      }
    }

    // test result string truncate
    if (!isSpark2) {
      result = sparkInterpreter.interpret(
          "val df=sqlContext.createDataFrame(Seq((\"12characters12characters\",20)))"
              + ".toDF(\"col_1\", \"col_2\")\n"
              + "df.collect()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertTrue(result.message().get(0).getData()
          .contains("Array[org.apache.spark.sql.Row] = Array([12characters12characters,20])"));
    } else {
      result = sparkInterpreter.interpret(
          "val df=spark.createDataFrame(Seq((\"12characters12characters\",20)))"
              + ".toDF(\"col_1\", \"col_2\")\n"
              + "df.collect()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertTrue(result.message().get(0).getData()
          .contains("Array[org.apache.spark.sql.Row] = Array([12characters12characters,20])"));
    }
    sparkInterpreter.interpret("df.registerTempTable(\"df\")", context);
    // test LivySparkSQLInterpreter which share the same SparkContext with LivySparkInterpreter
    result = sqlInterpreter.interpret("select * from df where col_1='12characters12characters'",
        context);
    assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
    assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
    assertEquals("col_1\tcol_2\n12characters12cha...\t20", result.message().get(0).getData());

  }

  @Test
  void testPySparkInterpreter() throws InterpreterException {
    if (!checkPreCondition()) {
      return;
    }

    final LivyPySparkInterpreter pysparkInterpreter = new LivyPySparkInterpreter(properties);
    pysparkInterpreter.setInterpreterGroup(mock(InterpreterGroup.class));
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    final InterpreterContext context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setAuthenticationInfo(authInfo)
        .setInterpreterOut(output)
        .build();
    pysparkInterpreter.open();

    // test traceback msg
    try {
      pysparkInterpreter.getLivyVersion();
      // for livy version >=0.3 , input some erroneous spark code, check the shown result is more
      // than one line
      InterpreterResult result = pysparkInterpreter.interpret(
          "sc.parallelize(wrongSyntax(1, 2)).count()", context);
      assertEquals(InterpreterResult.Code.ERROR, result.code());
      assertTrue(result.message().get(0).getData().split("\n").length > 1);
      assertTrue(result.message().get(0).getData().contains("Traceback"));
    } catch (APINotFoundException e) {
      // only livy 0.2 can throw this exception since it doesn't have /version endpoint
      // in livy 0.2, most error msg is encapsulated in evalue field, only print(a) in pyspark would
      // return none-empty traceback
      InterpreterResult result = pysparkInterpreter.interpret("print(a)", context);
      assertEquals(InterpreterResult.Code.ERROR, result.code());
      assertTrue(result.message().get(0).getData().split("\n").length > 1);
      assertTrue(result.message().get(0).getData().contains("Traceback"));
    }

    // test utf-8 Encoding
    String utf8Str = "你你你你你你好";
    InterpreterResult reslt = pysparkInterpreter.interpret("print(\"" + utf8Str + "\")", context);
    assertEquals(InterpreterResult.Code.SUCCESS, reslt.code());
    assertTrue(reslt.message().get(0).getData().contains(utf8Str));

    //test special characters
    String charStr = "açñiñíûÑoç";
    InterpreterResult res = pysparkInterpreter.interpret("print(\"" + charStr + "\")", context);
    assertEquals(InterpreterResult.Code.SUCCESS, res.code());
    assertTrue(res.message().get(0).getData().contains(charStr));

    try {
      InterpreterResult result = pysparkInterpreter.interpret("sc.version", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());

      boolean isSpark2 = isSpark2(pysparkInterpreter, context);

      // test RDD api
      result = pysparkInterpreter.interpret("sc.range(1, 10).sum()", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertTrue(result.message().get(0).getData().contains("45"));

      // test DataFrame api
      if (!isSpark2) {
        pysparkInterpreter.interpret("from pyspark.sql import SQLContext\n"
            + "sqlContext = SQLContext(sc)", context);
        result = pysparkInterpreter.interpret("df=sqlContext.createDataFrame([(\"hello\",20)])\n"
            + "df.collect()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        //python2 has u and python3 don't have u
        assertTrue(result.message().get(0).getData().contains("[Row(_1=u'hello', _2=20)]")
            || result.message().get(0).getData().contains("[Row(_1='hello', _2=20)]"));
      } else {
        result = pysparkInterpreter.interpret("df=spark.createDataFrame([('hello',20)])\n"
            + "df.collect()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        //python2 has u and python3 don't have u
        assertTrue(result.message().get(0).getData().contains("[Row(_1=u'hello', _2=20)]")
            || result.message().get(0).getData().contains("[Row(_1='hello', _2=20)]"));
      }

      // test magic api
      pysparkInterpreter.interpret("t = [{\"name\":\"userA\", \"role\":\"roleA\"},"
          + "{\"name\":\"userB\", \"role\":\"roleB\"}]", context);
      result = pysparkInterpreter.interpret("%table t", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
      assertTrue(result.message().get(0).getData().contains("userA"));

      // error
      result = pysparkInterpreter.interpret("print(a)", context);
      assertEquals(InterpreterResult.Code.ERROR, result.code());
      assertEquals(InterpreterResult.Type.TEXT, result.message().get(0).getType());
      assertTrue(result.message().get(0).getData().contains("name 'a' is not defined"));

      // cancel
      if (pysparkInterpreter.livyVersion.newerThanEquals(LivyVersion.LIVY_0_3_0)) {
        Thread cancelThread = new Thread() {
          @Override
          public void run() {
            // invoke cancel after 1 millisecond to wait job starting
            try {
              Thread.sleep(1);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
            pysparkInterpreter.cancel(context);
          }
        };
        cancelThread.start();
        result = pysparkInterpreter
            .interpret("import time\n" +
                "sc.range(1, 10).foreach(lambda a: time.sleep(10))", context);
        assertEquals(InterpreterResult.Code.ERROR, result.code());
        String message = result.message().get(0).getData();
        // 2 possibilities, sometimes livy doesn't return the real cancel exception
        assertTrue(message.contains("cancelled part of cancelled job group") ||
            message.contains("Job is cancelled"));
      }
    } finally {
      pysparkInterpreter.close();
    }
  }

  @Test
  void testSparkInterpreterStringWithoutTruncation()
      throws InterpreterException {
    if (!checkPreCondition()) {
      return;
    }
    InterpreterGroup interpreterGroup = new InterpreterGroup("group_1");
    interpreterGroup.put("session_1", new ArrayList<Interpreter>());
    Properties properties2 = new Properties(properties);
    // enable spark ui because it is disabled by livy integration test
    properties2.put("livy.spark.ui.enabled", "true");
    properties2.put(LivySparkSQLInterpreter.ZEPPELIN_LIVY_SPARK_SQL_FIELD_TRUNCATE, "false");
    LivySparkInterpreter sparkInterpreter = new LivySparkInterpreter(properties2);
    sparkInterpreter.setInterpreterGroup(interpreterGroup);
    interpreterGroup.get("session_1").add(sparkInterpreter);
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    InterpreterContext context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setAuthenticationInfo(authInfo)
        .setInterpreterOut(output)
        .build();
    sparkInterpreter.open();

    LivySparkSQLInterpreter sqlInterpreter = new LivySparkSQLInterpreter(properties2);
    interpreterGroup.get("session_1").add(sqlInterpreter);
    sqlInterpreter.setInterpreterGroup(interpreterGroup);
    sqlInterpreter.open();

    try {
      InterpreterResult result = sparkInterpreter.interpret("sc.version", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size(), result.toString());

      // html output
      String htmlCode = "println(\"%html <h1> hello </h1>\")";
      result = sparkInterpreter.interpret(htmlCode, context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertEquals(InterpreterResult.Type.HTML, result.message().get(0).getType());

      // detect spark version
      result = sparkInterpreter.interpret("sc.version", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());

      boolean isSpark2 = isSpark2(sparkInterpreter, context);

      if (!isSpark2) {
        result = sparkInterpreter.interpret(
            "val df=sqlContext.createDataFrame(Seq((\"12characters12characters\",20)))"
                + ".toDF(\"col_1\", \"col_2\")\n"
                + "df.collect()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(2, result.message().size());
        assertTrue(result.message().get(0).getData()
            .contains("Array[org.apache.spark.sql.Row] = Array([12characters12characters,20])"));
      } else {
        result = sparkInterpreter.interpret(
            "val df=spark.createDataFrame(Seq((\"12characters12characters\",20)))"
                + ".toDF(\"col_1\", \"col_2\")\n"
                + "df.collect()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData()
            .contains("Array[org.apache.spark.sql.Row] = Array([12characters12characters,20])"));
      }
      sparkInterpreter.interpret("df.registerTempTable(\"df\")", context);
      // test LivySparkSQLInterpreter which share the same SparkContext with LivySparkInterpreter
      result = sqlInterpreter.interpret("select * from df where col_1='12characters12characters'",
          context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
      assertEquals("col_1\tcol_2\n12characters12characters\t20", result.message().get(0).getData());
    } finally {
      sparkInterpreter.close();
      sqlInterpreter.close();
    }
  }

  @Test
  void testSparkRInterpreter() throws InterpreterException {
    if (!checkPreCondition()) {
      return;
    }

    final LivySparkRInterpreter sparkRInterpreter = new LivySparkRInterpreter(properties);
    sparkRInterpreter.setInterpreterGroup(mock(InterpreterGroup.class));

    try {
      sparkRInterpreter.getLivyVersion();
    } catch (APINotFoundException e) {
      // don't run sparkR test for livy 0.2 as there's some issues for livy 0.2
      return;
    }
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    final InterpreterContext context = InterpreterContext.builder()
        .setNoteId("noteId")
        .setParagraphId("paragraphId")
        .setAuthenticationInfo(authInfo)
        .setInterpreterOut(output)
        .build();
    sparkRInterpreter.open();

    try {
      // only test it in livy newer than 0.2.0
      boolean isSpark2 = isSpark2(sparkRInterpreter, context);
      InterpreterResult result = null;
      // test DataFrame api
      if (isSpark2) {
        result = sparkRInterpreter.interpret("df <- as.DataFrame(faithful)\nhead(df)", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData().contains("eruptions waiting"));

        // cancel
        Thread cancelThread = new Thread() {
          @Override
          public void run() {
            // invoke cancel after 1 millisecond to wait job starting
            try {
              Thread.sleep(1);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
            sparkRInterpreter.cancel(context);
          }
        };
        cancelThread.start();
        result = sparkRInterpreter.interpret("df <- as.DataFrame(faithful)\n" +
            "df1 <- dapplyCollect(df, function(x) " +
            "{ Sys.sleep(10); x <- cbind(x, x$waiting * 60) })", context);
        assertEquals(InterpreterResult.Code.ERROR, result.code());
        String message = result.message().get(0).getData();
        // 2 possibilities, sometimes livy doesn't return the real cancel exception
        assertTrue(message.contains("cancelled part of cancelled job group") ||
            message.contains("Job is cancelled"));
      } else {
        result = sparkRInterpreter.interpret("df <- createDataFrame(sqlContext, faithful)" +
            "\nhead(df)", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData().contains("eruptions waiting"));
      }

      // error
      result = sparkRInterpreter.interpret("cat(a)", context);
      assertEquals(InterpreterResult.Code.ERROR, result.code());
      assertEquals(InterpreterResult.Type.TEXT, result.message().get(0).getType());
      assertTrue(result.message().get(0).getData().contains("object 'a' not found"));
    } finally {
      sparkRInterpreter.close();
    }
  }

  @Test
  void testLivyParams() throws InterpreterException {
    if (!checkPreCondition()) {
      return;
    }
    InterpreterGroup interpreterGroup = new InterpreterGroup("group_1");
    interpreterGroup.put("session_1", new ArrayList<Interpreter>());
    Properties props = new Properties(properties);
    props.setProperty("livy.spark.executor.cores", "4");
    props.setProperty("livy.name", "zeppelin-livy");
    LivySparkInterpreter sparkInterpreter = new LivySparkInterpreter(props);
    sparkInterpreter.setInterpreterGroup(interpreterGroup);
    interpreterGroup.get("session_1").add(sparkInterpreter);
    AuthenticationInfo authInfo = new AuthenticationInfo("user1");
    MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
    InterpreterOutput output = new InterpreterOutput(outputListener);
    InterpreterContext context = InterpreterContext.builder()
            .setNoteId("noteId")
            .setParagraphId("paragraphId")
            .setAuthenticationInfo(authInfo)
            .setInterpreterOut(output)
            .build();
    sparkInterpreter.open();

    try {
      InterpreterResult result = sparkInterpreter.interpret("sc.version\n" +
              "assert(sc.getConf.get(\"spark.executor.cores\") == \"4\" && " +
                     "sc.getConf.get(\"spark.app.name\") == \"zeppelin-livy\")"
              , context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
    } finally {
      sparkInterpreter.close();
    }
  }

  @Test
  @Disabled("ZEPPELIN-6134: failed due to a livy-side(likely) classloader issue")
  void testLivyTutorialNote() throws IOException, InterpreterException {
    if (!checkPreCondition()) {
      return;
    }
    InterpreterGroup interpreterGroup = new InterpreterGroup("group_1");
    interpreterGroup.put("session_1", new ArrayList<Interpreter>());
    LazyOpenInterpreter sparkInterpreter = new LazyOpenInterpreter(
        new LivySparkInterpreter(properties));
    sparkInterpreter.setInterpreterGroup(interpreterGroup);
    interpreterGroup.get("session_1").add(sparkInterpreter);
    LazyOpenInterpreter sqlInterpreter = new LazyOpenInterpreter(
        new LivySparkSQLInterpreter(properties));
    interpreterGroup.get("session_1").add(sqlInterpreter);
    sqlInterpreter.setInterpreterGroup(interpreterGroup);
    sqlInterpreter.open();

    try {
      AuthenticationInfo authInfo = new AuthenticationInfo("user1");
      MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
      InterpreterOutput output = new InterpreterOutput(outputListener);
      InterpreterContext context = InterpreterContext.builder()
          .setNoteId("noteId")
          .setParagraphId("paragraphId")
          .setAuthenticationInfo(authInfo)
          .setInterpreterOut(output)
          .build();

      String p1 = IOUtils.toString(getClass().getResourceAsStream("/livy_tutorial_1.scala"),
          StandardCharsets.UTF_8);
      InterpreterResult result = sparkInterpreter.interpret(p1, context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());

      String p2 = IOUtils.toString(getClass().getResourceAsStream("/livy_tutorial_2.sql"),
          StandardCharsets.UTF_8);
      result = sqlInterpreter.interpret(p2, context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(InterpreterResult.Type.TABLE, result.message().get(0).getType());
    } finally {
      sparkInterpreter.close();
      sqlInterpreter.close();
    }
  }

  @Test
  void testSharedInterpreter() throws InterpreterException {
    if (!checkPreCondition()) {
      return;
    }
    InterpreterGroup interpreterGroup = new InterpreterGroup("group_1");
    interpreterGroup.put("session_1", new ArrayList<Interpreter>());
    LazyOpenInterpreter sparkInterpreter = new LazyOpenInterpreter(
        new LivySparkInterpreter(properties));
    sparkInterpreter.setInterpreterGroup(interpreterGroup);
    interpreterGroup.get("session_1").add(sparkInterpreter);

    LazyOpenInterpreter sqlInterpreter = new LazyOpenInterpreter(
        new LivySparkSQLInterpreter(properties));
    interpreterGroup.get("session_1").add(sqlInterpreter);
    sqlInterpreter.setInterpreterGroup(interpreterGroup);

    LazyOpenInterpreter pysparkInterpreter = new LazyOpenInterpreter(
        new LivyPySparkInterpreter(properties));
    interpreterGroup.get("session_1").add(pysparkInterpreter);
    pysparkInterpreter.setInterpreterGroup(interpreterGroup);

    LazyOpenInterpreter sparkRInterpreter = new LazyOpenInterpreter(
        new LivySparkRInterpreter(properties));
    interpreterGroup.get("session_1").add(sparkRInterpreter);
    sparkRInterpreter.setInterpreterGroup(interpreterGroup);

    LazyOpenInterpreter sharedInterpreter = new LazyOpenInterpreter(
        new LivySharedInterpreter(properties));
    interpreterGroup.get("session_1").add(sharedInterpreter);
    sharedInterpreter.setInterpreterGroup(interpreterGroup);

    sparkInterpreter.open();
    sqlInterpreter.open();
    pysparkInterpreter.open();
    sparkRInterpreter.open();

    try {
      AuthenticationInfo authInfo = new AuthenticationInfo("user1");
      MyInterpreterOutputListener outputListener = new MyInterpreterOutputListener();
      InterpreterOutput output = new InterpreterOutput(outputListener);
      InterpreterContext context = InterpreterContext.builder()
          .setNoteId("noteId")
          .setParagraphId("paragraphId")
          .setAuthenticationInfo(authInfo)
          .setInterpreterOut(output)
          .build();
      // detect spark version
      InterpreterResult result = sparkInterpreter.interpret("sc.version", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());

      boolean isSpark2 = isSpark2((BaseLivyInterpreter) sparkInterpreter.getInnerInterpreter(),
          context);

      if (!isSpark2) {
        result = sparkInterpreter.interpret(
            "val df=sqlContext.createDataFrame(Seq((\"hello\",20))).toDF(\"col_1\", \"col_2\")\n"
                + "df.collect()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData()
            .contains("Array[org.apache.spark.sql.Row] = Array([hello,20])"));
        sparkInterpreter.interpret("df.registerTempTable(\"df\")", context);

        // access table from pyspark
        result = pysparkInterpreter.interpret("sqlContext.sql(\"select * from df\").show()",
            context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData()
            .contains("+-----+-----+\n" +
                "|col_1|col_2|\n" +
                "+-----+-----+\n" +
                "|hello|   20|\n" +
                "+-----+-----+"));

        // access table from sparkr
        result = sparkRInterpreter.interpret("head(sql(sqlContext, \"select * from df\"))",
            context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData().contains("col_1 col_2\n1 hello    20"));
      } else {
        result = sparkInterpreter.interpret(
            "val df=spark.createDataFrame(Seq((\"hello\",20))).toDF(\"col_1\", \"col_2\")\n"
                + "df.collect()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData()
            .contains("Array[org.apache.spark.sql.Row] = Array([hello,20])"));
        sparkInterpreter.interpret("df.registerTempTable(\"df\")", context);

        // access table from pyspark
        result = pysparkInterpreter.interpret("spark.sql(\"select * from df\").show()", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData()
            .contains("+-----+-----+\n" +
                "|col_1|col_2|\n" +
                "+-----+-----+\n" +
                "|hello|   20|\n" +
                "+-----+-----+"));

        // access table from sparkr
        result = sparkRInterpreter.interpret("head(sql(\"select * from df\"))", context);
        assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
        assertEquals(1, result.message().size());
        assertTrue(result.message().get(0).getData().contains("col_1 col_2\n1 hello    20"));
      }

      // test plotting of python
      result = pysparkInterpreter.interpret(
          "import matplotlib.pyplot as plt\n" +
              "plt.switch_backend('agg')\n" +
              "data=[1,2,3,4]\n" +
              "plt.figure()\n" +
              "plt.plot(data)\n" +
              "%matplot plt", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertEquals(InterpreterResult.Type.IMG, result.message().get(0).getType());

      // test plotting of R
      result = sparkRInterpreter.interpret(
          "hist(mtcars$mpg)", context);
      assertEquals(InterpreterResult.Code.SUCCESS, result.code(), result.toString());
      assertEquals(1, result.message().size());
      assertEquals(InterpreterResult.Type.IMG, result.message().get(0).getType());

      // test code completion
      List<InterpreterCompletion> completionResult = sparkInterpreter
          .completion("df.sho", 6, context);
      assertEquals(1, completionResult.size());
      assertEquals("show", completionResult.get(0).name);

    } finally {
      sparkInterpreter.close();
      sqlInterpreter.close();
    }
  }

  private boolean isSpark2(BaseLivyInterpreter interpreter, InterpreterContext context) {
    if (interpreter instanceof LivySparkRInterpreter) {
      InterpreterResult result = interpreter.interpret("sparkR.session()", context);
      // SparkRInterpreter would always return SUCCESS, it is due to bug of LIVY-313
      return !result.message().get(0).getData().contains("Error");
    } else {
      InterpreterResult result = interpreter.interpret("spark", context);
      return result.code() == InterpreterResult.Code.SUCCESS;
    }
  }

  public static class MyInterpreterOutputListener implements InterpreterOutputListener {
    @Override
    public void onAppend(int index, InterpreterResultMessageOutput out, byte[] line) {
    }

    @Override
    public void onUpdate(int index, InterpreterResultMessageOutput out) {

    }

    @Override
    public void onUpdateAll(InterpreterOutput out) {

    }
  }
}

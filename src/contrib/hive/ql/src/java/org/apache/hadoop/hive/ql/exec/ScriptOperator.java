/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec;

import java.util.*;
import java.io.*;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.plan.scriptDesc;
import org.apache.hadoop.hive.ql.plan.tableDesc;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde.*;
import org.apache.hadoop.hive.ql.io.*;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.mapred.LineRecordReader.LineReader;
import org.apache.hadoop.util.StringUtils;


public class ScriptOperator extends Operator<scriptDesc> implements Serializable {

  private static final long serialVersionUID = 1L;


  public static enum Counter {DESERIALIZE_ERRORS, SERIALIZE_ERRORS}
  transient private LongWritable deserialize_error_count = new LongWritable ();
  transient private LongWritable serialize_error_count = new LongWritable ();

  transient DataOutputStream scriptOut;
  transient DataInputStream scriptErr;
  transient DataInputStream scriptIn;
  transient Thread outThread;
  transient Thread errThread;
  transient Process scriptPid;
  transient Configuration hconf;
  transient SerDe decoder;
  transient HiveObjectSerializer hos;
  transient volatile Throwable scriptError = null;

  /**
   * addJobConfToEnvironment is shamelessly copied from hadoop streaming.
   */
  static String safeEnvVarName(String var) {
    StringBuffer safe = new StringBuffer();
    int len = var.length();
    for (int i = 0; i < len; i++) {
      char c = var.charAt(i);
      char s;
      if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
        s = c;
      } else {
        s = '_';
      }
      safe.append(s);
    }
    return safe.toString();
  }
  
  static void addJobConfToEnvironment(Configuration conf, Map<String, String> env) {
    Iterator<Map.Entry<String, String>> it = conf.iterator();
    while (it.hasNext()) {
      Map.Entry<String, String> en = (Map.Entry<String, String>) it.next();
      String name = (String) en.getKey();
      //String value = (String)en.getValue(); // does not apply variable expansion
      String value = conf.get(name); // does variable expansion 
      name = safeEnvVarName(name);
      env.put(name, value);
    }
  }

  public void initialize(Configuration hconf) throws HiveException {
    super.initialize(hconf);
    statsMap.put(Counter.DESERIALIZE_ERRORS, deserialize_error_count);
    statsMap.put(Counter.SERIALIZE_ERRORS, serialize_error_count);

    try {
      this.hconf = hconf;

      tableDesc td = conf.getScriptOutputInfo();
      if(td == null) {
        td = Utilities.defaultTabTd;
      }
      decoder = td.getSerdeClass().newInstance();
      decoder.initialize(hconf, td.getProperties());

      hos = new NaiiveJSONSerializer();
      Properties p = new Properties ();
      p.setProperty(org.apache.hadoop.hive.serde.Constants.SERIALIZATION_FORMAT, ""+Utilities.tabCode);
      hos.initialize(p);

      String [] cmdArgs = splitArgs(conf.getScriptCmd());
      String [] wrappedCmdArgs = addWrapper(cmdArgs);
      l4j.info("Executing " + Arrays.asList(wrappedCmdArgs));
      l4j.info("tablename=" + hconf.get(HiveConf.ConfVars.HIVETABLENAME.varname));
      l4j.info("partname=" + hconf.get(HiveConf.ConfVars.HIVEPARTITIONNAME.varname));
      l4j.info("alias=" + alias);

      ProcessBuilder pb = new ProcessBuilder(wrappedCmdArgs);
      Map<String, String> env = pb.environment();
      addJobConfToEnvironment(hconf, env);
      env.put(safeEnvVarName(HiveConf.ConfVars.HIVEALIAS.varname), String.valueOf(alias));
      scriptPid = pb.start();       // Runtime.getRuntime().exec(wrappedCmdArgs);

      scriptOut = new DataOutputStream(new BufferedOutputStream(scriptPid.getOutputStream()));
      scriptIn = new DataInputStream(new BufferedInputStream(scriptPid.getInputStream()));
      scriptErr = new DataInputStream(new BufferedInputStream(scriptPid.getErrorStream()));
      outThread = new StreamThread(scriptIn, new OutputStreamProcessor (), "OutputProcessor");
      outThread.start();
      errThread = new StreamThread(scriptErr, new ErrorStreamProcessor (), "ErrorProcessor");
      errThread.start();

    } catch (Exception e) {
      e.printStackTrace();
      throw new HiveException ("Cannot initialize ScriptOperator", e);
    }
  }

  public void process(HiveObject r) throws HiveException {
    if(scriptError != null) {
      throw new HiveException(scriptError);
    }
    try {
      hos.serialize(r, scriptOut);
    } catch (IOException e) {
      l4j.error("Error in writing to script: " + e.getMessage());
      scriptError = e;
      throw new HiveException(e);
    }
  }

  public void close(boolean abort) throws HiveException {

    boolean new_abort = abort;
    if(!abort) {
      // everything ok. try normal shutdown
      try {
        scriptOut.flush();
        scriptOut.close();
        int exitVal = scriptPid.waitFor();
        if (exitVal != 0) {
          l4j.error("Script failed with code " + exitVal);
          new_abort = true;
        };
      } catch (IOException e) {
        new_abort = true;
      } catch (InterruptedException e) { }
    }

    // the underlying hive object serializer keeps track of serialization errors
    if(hos != null) {
      serialize_error_count.set(hos.getWriteErrorCount());
    }

    try {
      // try these best effort
      outThread.join(0);
      errThread.join(0);
      scriptPid.destroy();
    } catch (Exception e) {}

    super.close(new_abort);

    if(new_abort && !abort) {
      throw new HiveException ("Hit error while closing ..");
    }
  }


  interface StreamProcessor {
    public void processLine(Text line) throws HiveException;
    public void close() throws HiveException;
  }


  class OutputStreamProcessor implements StreamProcessor {
    public OutputStreamProcessor () {}
    public void processLine(Text line) throws HiveException {
      HiveObject ho;
      try {
        Object ev = decoder.deserialize(line);
        ho = new TableHiveObject(ev, decoder);
      } catch (SerDeException e) {
        deserialize_error_count.set(deserialize_error_count.get()+1);
        return;
      }
      forward(ho);
    }
    public void close() {
    }
  }

  class ErrorStreamProcessor implements StreamProcessor {
    public ErrorStreamProcessor () {}
    public void processLine(Text line) throws HiveException {
      System.err.println(line.toString());
    }
    public void close() {
    }
  }


  class StreamThread extends Thread {

    InputStream in;
    StreamProcessor proc;
    String name;

    StreamThread(InputStream in, StreamProcessor proc, String name) {
      this.in = in;
      this.proc = proc;
      this.name = name;
      setDaemon(true);
    }

    public void run() {
      LineReader lineReader = null;
      try {
        Text row = new Text();
        lineReader = new LineReader((InputStream)in, hconf);

        while(true) {
          row.clear();
          long bytes = lineReader.readLine(row);
          if(bytes <= 0) {
            break;
          }
          proc.processLine(row);
        }
        l4j.info("StreamThread "+name+" done");

      } catch (Throwable th) {
        scriptError = th;
        l4j.warn(StringUtils.stringifyException(th));
      } finally {
        try {
          if(lineReader != null) {
            lineReader.close();
          }
          in.close();
          proc.close();
        } catch (Exception e) {
          l4j.warn(name + ": error in closing ..");
          l4j.warn(StringUtils.stringifyException(e));
        }
      }
    }
  }

  /**
   *  Wrap the script in a wrapper that allows admins to control
   **/
  protected String [] addWrapper(String [] inArgs) {
    String wrapper = HiveConf.getVar(hconf, HiveConf.ConfVars.SCRIPTWRAPPER);
    if(wrapper == null) {
      return inArgs;
    }

    String [] wrapComponents = splitArgs(wrapper);
    int totallength = wrapComponents.length + inArgs.length;
    String [] finalArgv = new String [totallength];
    for(int i=0; i<wrapComponents.length; i++) {
      finalArgv[i] = wrapComponents[i];
    }
    for(int i=0; i < inArgs.length; i++) {
      finalArgv[wrapComponents.length+i] = inArgs[i];
    }
    return (finalArgv);
  }


  // Code below shameless borrowed from Hadoop Streaming

  public static String[] splitArgs(String args) {
    final int OUTSIDE = 1;
    final int SINGLEQ = 2;
    final int DOUBLEQ = 3;

    ArrayList argList = new ArrayList();
    char[] ch = args.toCharArray();
    int clen = ch.length;
    int state = OUTSIDE;
    int argstart = 0;
    for (int c = 0; c <= clen; c++) {
      boolean last = (c == clen);
      int lastState = state;
      boolean endToken = false;
      if (!last) {
        if (ch[c] == '\'') {
          if (state == OUTSIDE) {
            state = SINGLEQ;
          } else if (state == SINGLEQ) {
            state = OUTSIDE;
          }
          endToken = (state != lastState);
        } else if (ch[c] == '"') {
          if (state == OUTSIDE) {
            state = DOUBLEQ;
          } else if (state == DOUBLEQ) {
            state = OUTSIDE;
          }
          endToken = (state != lastState);
        } else if (ch[c] == ' ') {
          if (state == OUTSIDE) {
            endToken = true;
          }
        }
      }
      if (last || endToken) {
        if (c == argstart) {
          // unquoted space
        } else {
          String a;
          a = args.substring(argstart, c);
          argList.add(a);
        }
        argstart = c + 1;
        lastState = state;
      }
    }
    return (String[]) argList.toArray(new String[0]);
  }
}
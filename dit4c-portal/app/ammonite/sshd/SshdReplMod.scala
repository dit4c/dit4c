/*
 * The MIT License (MIT)
 * Copyright (c) 2014 Li Haoyi (haoyi.sg@gmail.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package ammonite.sshd

import java.io.{InputStream, OutputStream, PrintStream}

import ammonite.ops.Path
import ammonite.sshd.util.Environment
import ammonite.util.{Bind, Ref}
import ammonite.runtime.Storage
import ammonite.repl.Repl

import scala.language.postfixOps

/**
 * An ssh server which serves ammonite repl as it's shell channel.
 * To start listening for incoming connections call
 * [[start()]] method. You can [[stop()]] the server at any moment.
 * It will also close all running sessions
 * @param sshConfig configuration of ssh server,
 *                  such as users credentials or port to be listening to
 * @param predef predef that will be installed on repl instances served by this server
 * @param replArgs arguments to pass to ammonite repl on initialization of the session
 */
class SshdReplMod(sshConfig: SshServerConfig,
               predef: String = "",
               defaultPredef: Boolean = true,
               wd: Path = ammonite.ops.pwd,
               replArgs: Seq[Bind[_]] = Nil,
               classloader: ClassLoader = SshdRepl.getClass.getClassLoader) {
  private lazy val sshd = SshServer(sshConfig, shellServer =
    SshdReplMod.runRepl(sshConfig.ammoniteHome, predef, defaultPredef, wd, replArgs, classloader))

  def port = sshd.getPort
  def start(): Unit = sshd.start()
  def stop(): Unit = sshd.stop()
  def stopImmediately(): Unit = sshd.stop(true)
}


object SshdReplMod {
  // Actually runs a repl inside of session serving a remote user shell.
  private def runRepl(homePath: Path,
                      predef: String,
                      defaultPredef: Boolean,
                      wd: Path,
                      replArgs: Seq[Bind[_]],
                      replServerClassLoader: ClassLoader)
                     (in: InputStream, out: OutputStream): Unit = {
    // since sshd server has it's own customised environment,
    // where things like System.out will output to the
    // server's console, we need to prepare individual environment
    // to serve this particular user's session
    val replSessionEnv = Environment(replServerClassLoader, in, out)
    Environment.withEnvironment(replSessionEnv) {
      try {
        val augmentedPredef = ammonite.Main.maybeDefaultPredef(
          defaultPredef,
          ammonite.main.Defaults.predefString
        )
        new Repl(
          in, out, out,
          new Storage.Folder(homePath), augmentedPredef + "\n" + predef,
          wd, Some(ammonite.main.Defaults.welcomeBanner), replArgs
        ).run()
      } catch {
        case any: Throwable =>
          val sshClientOutput = new PrintStream(out)
          sshClientOutput.println("What a terrible failure, the REPL just blow up!")
          any.printStackTrace(sshClientOutput)
      }
    }
  }
}

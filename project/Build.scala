import sbt._
import Keys._

object DIT4CBuild extends Build {
  lazy val dit4c = Project(
      id = "dit4c",
      base = file("."))//.aggregate(gatehouse, machineshop, highcommand)
    
//  lazy val highcommand = Project(
//      id = "dit4c-highcommand",
//      base = file("highcommand"))
//    
//  lazy val gatehouse = Project(
//      id = "dit4c-gatehouse",
//      base = file("gatehouse"))
//
//  lazy val machineshop = Project(
//      id = "dit4c-machineshop",
//      base = file("machineshop"))
}
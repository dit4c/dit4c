import sbtdocker.{ImageName, Dockerfile}
import DockerKeys._

name := "dit4c-switchboard"

fork in run := true

connectInput in run := true

dependencyOverrides := Set(
  "org.scala-lang" %  "scala-library"  % scalaVersion.value,
  "org.scala-lang" %  "scala-compiler" % scalaVersion.value
)

libraryDependencies ++= {
  val akkaV = "2.3.6"
  val akkaHttpV = "2.0-M2"
  val specs2V = "3.6.4"
  Seq(
    "com.typesafe.akka"   %%  "akka-actor"      % akkaV,
    "com.typesafe.akka"   %%  "akka-agent"      % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"    % akkaV   % "test",
    "com.typesafe.akka"   %%  "akka-http-experimental" % akkaHttpV,
    "com.typesafe.play"   %%  "play-json"       % "2.4.3",
    "ch.qos.logback"      %   "logback-classic" % "1.1.2",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "org.specs2"          %%  "specs2-core"     % specs2V % "test",
    "org.specs2"          %%  "specs2-matcher-extra" % specs2V % "test",
    "org.specs2"          %%  "specs2-mock"     % specs2V % "test",
    "org.specs2"          %%  "specs2-scalacheck" % specs2V % "test",
    "com.samskivert"      %   "jmustache"       % "1.11",
    "com.github.scopt"    %%  "scopt"           % "3.2.0",
    "org.bouncycastle"    %   "bcpkix-jdk15on"  % "1.52"
  )
}

net.virtualvoid.sbt.graph.Plugin.graphSettings

packSettings

packMain := Map("dit4c-switchboard" -> "dit4c.switchboard.Boot")

sbtdocker.Plugin.dockerSettings

// Make docker depend on the package task
docker <<= docker.dependsOn(pack)

// Docker build
dockerfile in docker := {
  import sbtdocker.Instructions._
  import sbtdocker._
  val appDir = (packTargetDir / "pack").value
  immutable.Dockerfile.empty
    .from("centos:7")
    .run("sh", "-c",
      """
      set -e
      set -x
      # Statically compile nginx, then remove build tools
      export NGINX_VERSION=1.9.7
      export NPS_VERSION=1.9.32.10
      export NGINX_CACHE_DIR=/var/cache/nginx
      export NGINX_LOG_DIR=/var/log/nginx
      export NGINX_RUN_DIR=/var/run/nginx
      groupadd -r nginx
      useradd -r -d /var/www -s /sbin/nologin -g nginx nginx
      mkdir -p $NGINX_CACHE_DIR $NGINX_LOG_DIR $NGINX_RUN_DIR /run
      chown nginx:nginx $NGINX_CACHE_DIR $NGINX_LOG_DIR $NGINX_RUN_DIR
      export BUILD_PACKAGES="openssl-devel gcc gcc-c++ pcre-devel zlib-devel make"
      yum install -y $BUILD_PACKAGES
      mkdir /src
      curl -L -s https://github.com/pagespeed/ngx_pagespeed/archive/v${NPS_VERSION}-beta.tar.gz \
        | tar xzv -C /src
      curl -L -s https://dl.google.com/dl/page-speed/psol/${NPS_VERSION}.tar.gz \
        | tar xzv -C /src/ngx_pagespeed-${NPS_VERSION}-beta
      curl -L -s https://github.com/nginx/nginx/archive/release-${NGINX_VERSION}.tar.gz \
        | tar xzv -C /src
      cd /src/nginx-release-${NGINX_VERSION}
      """+
      """
      auto/configure
        --prefix=/usr
        --conf-path=/etc/nginx/nginx.conf
        --http-client-body-temp-path=$NGINX_CACHE_DIR/client_body_temp
        --http-proxy-temp-path=$NGINX_CACHE_DIR/proxy_temp
        --http-log-path=/dev/stdout
        --error-log-path=/dev/stderr
        --pid-path=$NGINX_RUN_DIR/nginx.pid
        --lock-path=$NGINX_RUN_DIR/nginx.lock
        --with-http_auth_request_module
        --with-http_v2_module
        --with-http_ssl_module
        --without-http_ssi_module
        --without-http_userid_module
        --without-http_access_module
        --without-http_auth_basic_module
        --without-http_autoindex_module
        --without-http_geo_module
        --without-http_split_clients_module
        --without-http_fastcgi_module
        --without-http_uwsgi_module
        --without-http_scgi_module
        --without-http_memcached_module
        --without-http_limit_conn_module
        --without-http_limit_req_module
        --without-http_empty_gif_module
        --without-http_browser_module
        --add-module=/src/ngx_pagespeed-${NPS_VERSION}-beta
        || cat objs/autoconf.err
      """.replaceAll("\n","")+
      """
      make
      make install
      yum remove -y $BUILD_PACKAGES '*-devel'
      cd -
      rm -rf /src
      rm -rf /var/cache/apk/*
      """)
    .run("sh", "-c",
      """
      yum install java-1.8.0-openjdk -y
      """)
    .run("sh", "-c",
      """
      set -e
      nginx -V
      java -version
      """)
    .add(appDir, "/opt/dit4c-switchboard")
    .run("chmod", "+x", "/opt/dit4c-switchboard/bin/dit4c-switchboard")
    .volume("/etc/ssl")
    .volume("/etc/dit4c-switchboard.d")
    .user("nginx")
    .cmd("sh", "-c",
      """
      JAVA_OPTS="-Dsun.net.inetaddr.ttl=60"
      SSL_OPTS=""
      KEY_FILE="/etc/ssl/server.key"
      CERT_FILE="/etc/ssl/server.crt"
      if [ -e $KEY_FILE -o -e $CERT_FILE ]; then
        SSL_OPTS="-k $KEY_FILE -c $CERT_FILE"
      fi
      EXTRA_MAIN_CONFIG=${EXTRA_MAIN_CONFIG:-/etc/dit4c-switchboard.d/main.conf}
      EXTRA_VHOST_CONFIG=${EXTRA_VHOST_CONFIG:-/etc/dit4c-switchboard.d/vhost.conf}
      CONFIG_FILE_OPTS=""
      if [ -e $EXTRA_MAIN_CONFIG ]; then
        CONFIG_FILE_OPTS="$CONFIG_FILE_OPTS --extra-main-config $EXTRA_MAIN_CONFIG"
      fi
      if [ -e $EXTRA_VHOST_CONFIG ]; then
        CONFIG_FILE_OPTS="$CONFIG_FILE_OPTS --extra-vhost-config $EXTRA_VHOST_CONFIG"
      fi
      exec /opt/dit4c-switchboard/bin/dit4c-switchboard -f $DIT4C_ROUTE_FEED -p 8080 -d $DIT4C_DOMAIN $SSL_OPTS $CONFIG_FILE_OPTS
      """)
    .expose(8080)
}

// Set a custom image name
imageName in docker := {
  ImageName(namespace = Some("dit4c"),
    repository = "dit4c-platform-switchboard",
    tag = Some(version.value))
}

ReleaseKeys.publishArtifactsAction := docker.value

# DIT4C - Data Intensive Tools for the Cloud

[![Build Status](https://travis-ci.org/dit4c/dit4c.svg?branch=master)](https://travis-ci.org/dit4c/dit4c)
[![Coverage Status](https://coveralls.io/repos/dit4c/dit4c/badge.svg?branch=master&service=github)](https://coveralls.io/github/dit4c/dit4c?branch=master)

DIT4C is a scalable platform for providing containerized web-based programming and data analysis environments to researchers.

 * Client == Modern web browser
 * No local credentials: use your [GitHub][github] or [AAF][aaf] account
 * Based on [rkt](https://coreos.com/rkt/) - run ACIs or Docker images on bare metal or cloud computing

All authentication is via federated identity providers - all a user needs is a modern web browser.

Some of the current environments available are:

 * [Base][dit4c-container-base] - web-based TTY sessions and basic file management
 * [Jupyter Notebook + Base][dit4c-container-ipython]
 * [NLTK + Jupyter Notebook + Base][dit4c-container-nltk]
 * [OpenRefine + Base][dit4c-container-openrefine]
 * [RStudio + Base][dit4c-container-rstudio]
 * [Apache Zeppelin + Base][dit4c-container-zeppelin]
 * [X11][dit4c-container-x11] - Base + X11 sessions via HTML5 VNC client
 * [Octave + X11][dit4c-container-octave]
 * [QGIS + Jupyter + RStudio + X11][dit4c-container-zeppelin] - QGIS with supporting Python & R environments

## Motivation

DIT4C is focused on meeting two needs:

 * Training sessions - having a working install right from the beginning means training participants start programming sooner, and do so in a consistent environment.
 * Reproducible research - container sharing and export allows complete working environments to be exchanged and archived.


## Architecture

DIT4C separates the portal environment which manages user access and containers from the compute nodes that provide them.

Core services:
 * portal - user-facing UI and scheduler coordination
 * scheduler - manages compute clusters and schedules containers on individual nodes

Additional services:
 * image server - necessary for saving and exporting instances
     - [dit4c-imageserver-filesystem](https://github.com/dit4c/dit4c-imageserver-filesystem) - store images on a filesystem (possibly mounted via NFS)
     - [dit4c-imageserver-swift](https://github.com/dit4c/dit4c-imageserver-swift) - store images in OpenStack Swift object storage

Auxiliary "helper" container images:

 * dit4c-helper-listener-*
     - [dit4c-helper-listener-ngrok2](https://github.com/dit4c/dit4c-helper-listener-ngrok2) _(DEFAULT)_ - development image that exposes containers via [ngrok.com](https://ngrok.com/) (don't use this in production)
     - [dit4c-helper-listener-ssh](https://github.com/dit4c/dit4c-helper-listener-ssh) - expose containers using SSH reverse port forwarding and reverse-proxy server. When used with [dit4c-routingserver-ssh](https://github.com/dit4c/dit4c-helper-listener-ssh) it supports HTTP & HTTPS.
     - [dit4c-helper-listener-ngrok1](https://github.com/dit4c/dit4c-helper-listener-ngrok1) _(DEPRECATED)_ - expose containers via your own ngrok1 servers. Supports HTTP, HTTPS & individual TCP ports.
 * [dit4c-helper-auth-portal](https://github.com/dit4c/dit4c-helper-auth-portal/) - proxies container services behind portal-provided auth
 * [dit4c-helper-upload-webdav](https://github.com/dit4c/dit4c-helper-upload-webdav/) - uploads saved images to a webdav server

_Many things have changed in DIT4C 0.10. An updated architecture diagram will be added soon._


### Security

All container instances are issued an OpenPGP key prior to starting which is convertible to a [JSON Web Key (JWK)](https://tools.ietf.org/html/draft-ietf-jose-json-web-key-41) or SSH key. This allows container helpers to independently contact the portal to update and retrieve information using a signed [JSON Web Token (JWT)](https://jwt.io/).

The portal also provides keys via a public registry, which will allow future helpers to authenticate independently to other services or retrieve encrypted content. This is still a work in progress.

### Installation Requirements

#### Servers

While DIT4C _could_ be run on a single server for development purposes, a secure installation will attempt to segregate the portal, scheduler and compute onto separate physical or virtual machines.

A valid minimal configuration would include:
 * portal/image server (public ports exposed: HTTPS)
   - nghttpx/nginx as HTTPS reverse-proxy
   - portal
   - image server
   - [Cassandra](http://cassandra.apache.org/) database for portal
 * scheduler (public ports exposed: none)
   - scheduler
   - [Cassandra](http://cassandra.apache.org/) database for scheduler
 * compute node (public ports exposed: none)
   - no installed software required
   - [CoreOS](https://coreos.com/) recommended
   - SSH port must be accessible by scheduler


#### TLS Certificates

HTTPS should be used with the portal, image server (recommended, but optional) and routing server. [Let's Encrypt](https://letsencrypt.org/) is sufficient for the portal and image server, but all HTTPS routing server implementations require a wildcard certificate. While highly discouraged, a self-signed certificate can be used for the routing server if necessary (presumably while a valid wildcard certificate is being sourced).


## Installing

_Many things have changed in DIT4C 0.10. Updated installation instructions will be added soon._


[swc]: http://software-carpentry.org/
[aaf]: https://aaf.edu.au/
[rapidaaf]: https://rapid.aaf.edu.au/
[github]: https://github.com/
[github-auth]: https://developer.github.com/guides/basics-of-authentication/#registering-your-app
[coreos]: https://coreos.com/
[dit4c-container-base]: https://registry.hub.docker.com/u/dit4c/dit4c-container-base/
[dit4c-container-ipython]: https://registry.hub.docker.com/u/dit4c/dit4c-container-ipython/
[dit4c-container-nltk]: https://registry.hub.docker.com/u/dit4c/dit4c-container-nltk/
[dit4c-container-octave]: https://registry.hub.docker.com/u/dit4c/dit4c-container-octave/
[dit4c-container-openrefine]: https://registry.hub.docker.com/u/dit4c/dit4c-container-openrefine/
[dit4c-container-qgis]: https://registry.hub.docker.com/u/dit4c/dit4c-container-qgis/
[dit4c-container-rstudio]: https://registry.hub.docker.com/u/dit4c/dit4c-container-rstudio/
[dit4c-container-x11]: https://registry.hub.docker.com/u/dit4c/dit4c-container-x11/
[dit4c-container-zeppelin]: https://registry.hub.docker.com/u/dit4c/dit4c-container-zeppelin/
[dit4c-deploy-routing]: https://registry.hub.docker.com/u/dit4c/dit4c-deploy-routing/
[dit4c-deploy-portal]: https://registry.hub.docker.com/u/dit4c/dit4c-deploy-portal/
[dit4c-deploy-compute]: https://registry.hub.docker.com/u/dit4c/dit4c-deploy-compute/
[dit4c-cluster-manager]: https://registry.hub.docker.com/u/dit4c/dit4c-cluster-manager/

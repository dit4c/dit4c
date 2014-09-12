# DIT4C - Data Intensive Tools for the Cloud

DIT4C is a scalable platform for providing containerized web-based programming and data analysis environments to researchers.

 * Client == Modern web browser
 * No local credentials: use your [GitHub][github] or [AAF][aaf] account
 * Based on Docker - runs on bare metal or cloud computing

All authentication is via federated identity providers - all a user needs is a modern web browser. 

Current environments available are:
 * [Base][dit4c-container-base] - web-based TTY sessions and basic file management
 * [iPython Notebooks + Base][dit4c-container-ipython]
 * [RStudio + Base][dit4c-container-rstudio]

## Motivation
The primary focus of DIT4C is [Software Carpentry Bootcamps][swc]. Having a working install right from the beginning means participants start programming sooner, and do so in a consistent environment.

## Architecture

DIT4C separates the portal environment which manages user access and containers from the compute nodes that provide them.

### Components

 * __highcommand__ - user authentication and container management portal.
 * __gatehouse__ - authorization checker for containers.
 * __machineshop__ - high-level API server for container management.




[swc]: http://software-carpentry.org/
[aaf]: https://aaf.edu.au/
[github]: https://github.com/
[dit4c-container-base]: https://registry.hub.docker.com/u/dit4c/dit4c-container-base/
[dit4c-container-ipython]: https://registry.hub.docker.com/u/dit4c/dit4c-container-ipython/
[dit4c-container-rstudio]: https://registry.hub.docker.com/u/dit4c/dit4c-container-rstudio/


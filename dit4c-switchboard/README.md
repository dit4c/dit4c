dit4c-switchboard
=================

Switchboard replaces the direct "shared database" model used for DIT4C subdomain
routes. Instead, Switchboard listens for route changes emitted by Highcommand
and updates in real-time.

It monitors and configures an [Nginx](http://nginx.org/) server rather than directly proxying requests. Updates trigger an Nginx configuration reload, which Nginx is designed to handle without dropping existing requests.

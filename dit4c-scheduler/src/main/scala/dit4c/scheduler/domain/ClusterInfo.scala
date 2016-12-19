package dit4c.scheduler.domain

case class ClusterInfo(
    displayName: String,
    active: Boolean,
    supportsSave: Boolean)
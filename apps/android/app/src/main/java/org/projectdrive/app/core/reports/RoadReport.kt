package org.projectdrive.app.core.reports

import org.projectdrive.app.core.navigation.*

enum class ReportCategory(val wire:String,val label:String,val lifetime:String) {
    Accident("accident","Accident","2 hours"), Breakdown("breakdown","Breakdown","1 hour"),
    Camera("camera","Camera","7 days"), SpeedBump("speed_bump","Speed bump","30 days"),
    Closure("closure","Road closure","6 hours"), Obstacle("obstacle","Obstacle","2 hours");
    companion object { fun from(value:String)=entries.first{it.wire==value} }
}
data class RoadReport(val id:String,val category:ReportCategory,val location:Coordinate,val note:String,
    val observedAt:Long,val expiresAt:Long,val mine:Boolean=false,val confirmations:Int=0,val dismissals:Int=0,
    val myVote:Int=0,val confidence:String="unverified",val status:String="active") {
    fun active(now:Long)=status=="active"&&expiresAt>now
}
/** Visual guidance only; camera warnings stay off pending jurisdiction review. */
fun upcomingReport(reports:List<RoadReport>,route:Route?,position:Coordinate?,now:Long):RoadReport? {
    if(route==null||position==null)return null
    val current=progressOnRoute(route,position)
    if(current.arrived||current.distanceToRouteMeters>60)return null
    return reports.asSequence().filter{it.active(now)&&it.confidence!="disputed"&&it.category!=ReportCategory.Camera}
        .map{it to progressOnRoute(route,it.location)}
        .filter{(_,p)->p.distanceToRouteMeters<=100&&current.remainingMeters-p.remainingMeters in 0.0..1500.0}
        .minByOrNull{(_,p)->current.remainingMeters-p.remainingMeters}?.first
}

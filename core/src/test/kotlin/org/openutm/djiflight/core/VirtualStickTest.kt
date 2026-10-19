package org.openutm.djiflight.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.openutm.djiflight.core.adapter.MissionEvent
import org.openutm.djiflight.core.geo.*
import org.openutm.djiflight.core.mission.*
import kotlin.math.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualStickTest {
    private val origin = TestData.NAIROBI
    private fun state(t:Long=1_000) = StickState(t,origin,40.0,0.0,true,true,false,true,80,0,40.0,true)
    private fun wp(bearing:Double=0.0,distance:Double=30.0):MissionWaypoint = Geo.destination(origin,bearing,distance).let { MissionWaypoint(it.lat,it.lng,40.0) }
    @Test fun `guidance preserves world axes and clamps horizontal vertical and yaw speeds`() {
        val north=VirtualStickGuidance(origin).step(1_000,state(),wp(),15.0).command
        assertTrue(north.northMps in 0.0..3.0);assertEquals(0.0,north.eastMps,0.001)
        val east=VirtualStickGuidance(origin).step(1_000,state().copy(yawDeg=90.0),wp(90.0).copy(heightM=41.0),15.0).command
        assertTrue(east.eastMps>0);assertEquals(0.0,east.northMps,0.001);assertTrue(abs(east.upMps)<=1)
        val yaw=VirtualStickGuidance(origin).step(1_000,state(),wp(180.0),3.0).command
        assertTrue(abs(yaw.yawRateDegS)<=15); assertEquals(0.0,yaw.northMps)
    }
    @Test fun `stale telemetry bad GPS pilot override low reserve and missing authority stop navigation`() {
        val cases=listOf(state(0),state().copy(gpsHealthy=false),state().copy(pilotOverride=true),state().copy(batteryPercent=25),state().copy(batteryPercent=null),state().copy(authorityAvailable=false),state().copy(heightM=Double.NaN),state().copy(airborne=false))
        cases.forEach { s -> val r=VirtualStickGuidance(origin).step(1_000,s,wp(),3.0);assertNotNull(r.failure);assertEquals(StickCommand(),r.command) }
        assertNotNull(VirtualStickGuidance(origin).step(1_000,state(),wp(distance=201.0),3.0).failure)
    }
    @Test fun `missed control deadline and reversed clock fail closed`() {
        val g=VirtualStickGuidance(origin);g.step(1_000,state(),wp(),3.0)
        assertNotNull(g.step(1_600,state(1_600),wp(),3.0).failure)
        assertNotNull(g.step(1_500,state(1_500),wp(),3.0).failure)
    }
    @Test fun `fresh but frozen navigation aborts while an intentional pause does not`() {
        val g=VirtualStickGuidance(origin)
        var failed=false
        for(t in 1_000L..17_000L step 100)if(g.step(t,state(t),wp(),3.0).failure!=null)failed=true
        assertTrue(failed)
        val held=VirtualStickGuidance(origin)
        for(t in 1_000L..17_000L step 100)assertNull(held.step(t,state(t),wp(),3.0,held=true).failure)
    }
    private class Port(val scheduler:TestCoroutineScheduler):VirtualStickPort {
        var current=StickState(0,TestData.NAIROBI,40.0,0.0,true,true,false,true,80,0,40.0,true)
        var releaseWorks=true;var fresh=true;var released=0;var commands=0;var captureWorks=true;var rth=false
        override fun nowMs()=scheduler.currentTime
        override fun state()=current.copy(receivedMs=if(fresh) nowMs() else 0)
        override suspend fun enable()=Result.success(Unit)
        override suspend fun release():Result<Unit>{released++;return if(releaseWorks) Result.success(Unit) else Result.failure(IllegalStateException("SDK refused release"))}
        override suspend fun prepareCamera()=Result.success(Unit)
        override suspend fun pitch(degrees:Double)=Result.success(Unit)
        override suspend fun shoot():Result<Unit>{if(captureWorks)current=current.copy(photoCount=current.photoCount+1);return Result.success(Unit)}
        override suspend fun returnHome():Result<Unit>{rth=true;return Result.success(Unit)}
        override suspend fun command(value:StickCommand):Result<Unit>{
            commands++
            val speed=hypot(value.northMps,value.eastMps)
            current=current.copy(position=Geo.destination(current.position,Math.toDegrees(atan2(value.eastMps,value.northMps)),speed*0.1),heightM=current.heightM+value.upMps*0.1,yawDeg=current.yawDeg+value.yawRateDegS*0.1)
            return Result.success(Unit)
        }
    }
    private fun plan()=MissionPlan(listOf(wp(distance=0.0),wp(distance=10.0)),3.0,photoDistanceIntervalM=5.0)
    @Test fun `operator completes capture route releases authority and requests RTH`()=runTest {
        val port=Port(testScheduler);val op=VirtualStickMission(port,backgroundScope);val events=mutableListOf<MissionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){op.events.collect { events+=it }}
        assertTrue(op.upload(plan()).isSuccess);assertTrue(op.start().isSuccess)
        advanceTimeBy(30_000);runCurrent()
        assertTrue(port.rth);assertTrue(port.released>0);assertTrue(port.current.photoCount>=3)
        assertTrue(events.any { it is MissionEvent.Finished && it.error==null })
    }
    @Test fun `stop and scope cancellation release control without issuing RTH`()=runTest {
        val port=Port(testScheduler);val op=VirtualStickMission(port,backgroundScope)
        op.upload(plan());op.start();advanceTimeBy(200);op.stop()
        assertTrue(port.released>0);assertFalse(port.rth)
        val commands=port.commands;advanceTimeBy(1_000);assertEquals(commands,port.commands)
    }
    @Test fun `parent cancellation still releases command authority`()=runTest {
        val port=Port(testScheduler)
        val owner=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val op=VirtualStickMission(port,owner)
        op.upload(plan());op.start();advanceTimeBy(200);owner.cancel();runCurrent()
        assertTrue(port.released>0);assertFalse(port.rth)
    }
    @Test fun `paused missions hold and can resume without another authority acquisition`()=runTest {
        val port=Port(testScheduler);val op=VirtualStickMission(port,backgroundScope)
        op.upload(plan());op.start();advanceTimeBy(2_000);op.pause()
        val position=port.current.position;advanceTimeBy(2_000)
        assertEquals(position,port.current.position);assertEquals(0,port.released)
        op.resume();advanceTimeBy(30_000);runCurrent();assertTrue(port.rth)
    }
    @Test fun `unknown failsafe or RTH outside declared margin prevents acquisition`()=runTest {
        for(s in listOf(Port(testScheduler).current.copy(failsafeReturnsHome=false),Port(testScheduler).current.copy(returnHomeHeightM=null),Port(testScheduler).current.copy(returnHomeHeightM=100.0))) {
            val port=Port(testScheduler);port.current=s
            val op=VirtualStickMission(port,backgroundScope);op.upload(plan())
            assertTrue(op.start().isFailure);assertEquals(0,port.commands);assertEquals(0,port.released)
        }
    }
    @Test fun `failed authority release is reported and must not be called a completed mission`()=runTest {
        val port=Port(testScheduler);port.releaseWorks=false
        val op=VirtualStickMission(port,backgroundScope);val events=mutableListOf<MissionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){op.events.collect { events+=it }}
        op.upload(plan());op.start();advanceTimeBy(30_000);runCurrent()
        assertFalse(port.rth)
        assertTrue(events.any { it is MissionEvent.Finished && it.error?.contains("release") == true })
        assertTrue(op.stop().isFailure)
    }
    @Test fun `double start must not release the already running mission`()=runTest {
        val port=Port(testScheduler);val op=VirtualStickMission(port,backgroundScope)
        op.upload(plan());op.start();runCurrent()
        assertTrue(op.start().isFailure);assertEquals(0,port.released)
        op.stop()
    }
    @Test fun `physical override releases control without another command`()=runTest {
        val port=Port(testScheduler);val op=VirtualStickMission(port,backgroundScope)
        op.upload(plan());op.start();advanceTimeBy(200);port.current=port.current.copy(pilotOverride=true)
        val sent=port.commands;advanceTimeBy(1_000);runCurrent()
        assertEquals(sent,port.commands);assertTrue(port.released>0);assertFalse(port.rth)
    }
    @Test fun `missing photo acknowledgement interrupts rather than silently losing coverage`()=runTest {
        val port=Port(testScheduler);port.captureWorks=false
        val op=VirtualStickMission(port,backgroundScope);val events=mutableListOf<MissionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){op.events.collect { events+=it }}
        op.upload(plan());op.start();advanceTimeBy(10_000);runCurrent()
        assertTrue(port.released>0);assertFalse(port.rth)
        assertTrue(events.any { it is MissionEvent.Finished && it.error!=null })
    }
}

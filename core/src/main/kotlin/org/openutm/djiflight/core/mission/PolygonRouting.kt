package org.openutm.djiflight.core.mission

import org.openutm.djiflight.core.geo.*
import kotlin.math.*

/** Visibility routing inside a simple polygon, so scan lines do not bridge concave recesses. */
internal class PolygonRouting(ring: List<LatLng>) {
    private val plane = LocalPlane(ring.first())
    private val vertices = ring.dropLast(1).map(plane::toXY)
    private fun cross(a: XY,b: XY) = a.x*b.y-a.y*b.x
    private fun minus(a: XY,b: XY) = XY(a.x-b.x,a.y-b.y)
    init {
        require(vertices.size >= 3 && vertices.distinct().size == vertices.size) { "polygon corners must be distinct" }
        val area = vertices.indices.sumOf { cross(vertices[it],vertices[(it+1)%vertices.size]) }
        require(abs(area)>1) { "polygon has no usable area" }
        for(i in vertices.indices) for(j in i+1 until vertices.size) {
            if(j==i+1 || i==0 && j==vertices.lastIndex)continue
            val a=vertices[i];val b=vertices[(i+1)%vertices.size];val c=vertices[j];val d=vertices[(j+1)%vertices.size]
            val r=minus(b,a);val s=minus(d,c);val den=cross(r,s)
            if(abs(den)>1e-8) {
                val t=cross(minus(c,a),s)/den;val u=cross(minus(c,a),r)/den
                require(!(t in 0.0..1.0 && u in 0.0..1.0)) { "polygon edges cross; redraw the boundary" }
            }
        }
    }
    private fun inside(p:XY):Boolean {
        var result=false
        for(i in vertices.indices) {
            val a=vertices[i];val b=vertices[(i+1)%vertices.size]
            val dx=b.x-a.x;val dy=b.y-a.y
            val l=dx*dx+dy*dy
            val t=if(l==0.0)0.0 else (((p.x-a.x)*dx+(p.y-a.y)*dy)/l).coerceIn(0.0,1.0)
            if(hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)<0.001)return true
            if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)result=!result
        }
        return result
    }
    private fun visible(a:XY,b:XY):Boolean {
        val r=minus(b,a);val ts=mutableListOf(0.0,1.0)
        for(i in vertices.indices) {
            val c=vertices[i];val s=minus(vertices[(i+1)%vertices.size],c);val den=cross(r,s)
            if(abs(den)<1e-8)continue
            val t=cross(minus(c,a),s)/den;val u=cross(minus(c,a),r)/den
            if(t in 0.0..1.0 && u in 0.0..1.0)ts+=t
        }
        return ts.sorted().zipWithNext().all { (lo,hi) -> val t=(lo+hi)/2; inside(XY(a.x+r.x*t,a.y+r.y*t)) }
    }
    private val edges by lazy {
        Array(vertices.size) { i -> vertices.indices.filter { j -> j!=i && visible(vertices[i],vertices[j]) } }
    }
    fun connect(from:LatLng,to:LatLng):List<LatLng> {
        val a=plane.toXY(from);val b=plane.toXY(to)
        if(visible(a,b))return emptyList()
        val n=vertices.size
        val dist=DoubleArray(n){Double.POSITIVE_INFINITY};val prev=IntArray(n){-1};val used=BooleanArray(n)
        for(i in vertices.indices)if(visible(a,vertices[i]))dist[i]=hypot(a.x-vertices[i].x,a.y-vertices[i].y)
        repeat(n) {
            val u=vertices.indices.filter { !used[it] }.minByOrNull { dist[it] } ?: return@repeat
            used[u]=true
            for(v in edges[u]) {
                val candidate=dist[u]+hypot(vertices[u].x-vertices[v].x,vertices[u].y-vertices[v].y)
                if(candidate<dist[v]){dist[v]=candidate;prev[v]=u}
            }
        }
        val last=vertices.indices.filter { visible(vertices[it],b) }.minByOrNull { dist[it]+hypot(vertices[it].x-b.x,vertices[it].y-b.y) }
        require(last!=null && dist[last].isFinite()) { "cannot connect survey strips inside this polygon" }
        val path=mutableListOf<LatLng>();var i:Int=requireNotNull(last)
        while(i>=0){path+=plane.toLatLng(vertices[i]);i=prev[i]}
        return path.reversed().filter { Geo.distanceM(from,it)>=0.5 && Geo.distanceM(to,it)>=0.5 }
    }
}

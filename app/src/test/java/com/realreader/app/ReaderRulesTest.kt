package com.realreader.app
import org.junit.Assert.*
import org.junit.Test
class ReaderRulesTest {
 @Test fun zoomPansBeforeTurning() {
  assertFalse(ReadingRules.edgeTurn(2f,0f,500f,-160f,0f))
  assertTrue(ReadingRules.edgeTurn(2f,-500f,500f,-160f,0f))
  assertFalse(ReadingRules.edgeTurn(2f,-500f,500f,160f,0f))
  assertFalse(ReadingRules.edgeTurn(1f,0f,0f,100f,120f))
 }
 @Test fun coversAndWideSpreadsStaySingle() {
  assertEquals(listOf(0,1,3,4,6),PageLayout.starts(booleanArrayOf(false,false,false,true,false,false,false),true))
  assertEquals((0..6).toList(),PageLayout.starts(BooleanArray(7),false))
 }
 @Test fun namesAndNaturalPageOrder() {
  assertEquals("Saga" to 12.5,ComicNames.parse("Saga #12.5.cbz"))
  assertTrue(ComicNames.natural("page2.jpg") < ComicNames.natural("page10.jpg"))
 }
 @Test fun creaseIsFiniteAndSpineIsBound() {
  for(t in listOf(.001f,.2f,.5f,.8f,1f))for(y in listOf(0f,.5f,1f))for(f in listOf(false,true)) {
   val mesh=FoldMesh.create(t,y,1-y,f)
   assertEquals(0,mesh.size%15)
   assertTrue(mesh.all {it.isFinite()})
   for(i in mesh.indices step 5)if(mesh[i+3]==0f)assertEquals(0f,mesh[i],.0001f)
  }
 }
}

/*
 * Copyright 2019 SimplexPortal Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplexportal.spatial

package model {

  object Location {
    val MAX_LONGITUDE = 180
    val MAX_LATITUDE = 90
    val MIN_LONGITUDE = -180
    val MIN_LATITUDE = -90
    val MAX = Location(MAX_LONGITUDE, MAX_LATITUDE)
    val MIN = Location(MIN_LONGITUDE, MIN_LATITUDE)
  }

  case class Location(lon: Double, lat: Double)

  object BoundingBox {
    val MAX = BoundingBox(Location.MIN, Location.MAX)
  }

  case class BoundingBox(min: Location, max: Location)

}

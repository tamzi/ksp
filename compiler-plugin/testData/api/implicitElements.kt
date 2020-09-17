/*
 * Copyright 2010-2020 Google LLC, JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// TEST PROCESSOR: ImplicitElementProcessor
// EXPECTED:
// <init>; origin: SYNTHETIC
// <null>
// <null>
// readOnly.get(): SYNTHETIC annotations from property: GetAnno
// readOnly.getter.owner: readOnly: KOTLIN
// readWrite.get(): KOTLIN
// readWrite.set(): SYNTHETIC annotations from property: SetAnno
// comp1.get(): SYNTHETIC
// comp2.get(): SYNTHETIC
// comp2.set(): SYNTHETIC
// GetAnno
// END
// FILE: a.kt
annotation class GetAnno
annotation class SetAnno

class Cls {
    @get:GetAnno
    val readOnly: Int = 1

    @set:SetAnno
    var readWrite: Int = 2
    get() = 1
}

data class Data(@get:GetAnno val comp1: Int, var comp2: Int)

interface ITF

// FILE: JavaClass.java
public class JavaClass {
    public JavaClass() { this(1); }
    public JavaClass(int a) { this(a, "ok"); }
    public JavaClass(int a, String s) { }
}

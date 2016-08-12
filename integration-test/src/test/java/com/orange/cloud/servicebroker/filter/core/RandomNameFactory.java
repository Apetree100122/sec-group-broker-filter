/*
 * <!--
 *
 *     Copyright (C) 2015 Orange
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 * -->
 */

package com.orange.cloud.servicebroker.filter.core;

import java.math.BigInteger;
import java.util.Random;

public final class RandomNameFactory implements NameFactory {

    private final Random random;

    RandomNameFactory(Random random) {
        this.random = random;
    }

    @Override
    public String getName(String prefix) {
        return String.format("%s%s", prefix, new BigInteger(25, this.random).toString(32));
    }

}

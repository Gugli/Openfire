/*
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
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

package org.jivesoftware.openfire.spi;

import org.apache.commons.lang.StringEscapeUtils;
import org.jivesoftware.openfire.StreamID;
import org.jivesoftware.openfire.StreamIDFactory;
import org.jivesoftware.util.cache.CacheSizes;
import org.jivesoftware.util.cache.Cacheable;
import org.jivesoftware.util.cache.CannotCalculateSizeException;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Random;

/**
 * A basic stream ID factory that produces IDs using a cryptographically strong random number generator.
 *
 * @author Iain Shigeoka
 */
public class BasicStreamIDFactory implements StreamIDFactory {

    /**
     * The maximum amount of characters in a stream ID that's generated by this implementation.
     */
    private static final int MAX_STRING_SIZE = 10;

    /**
     * The random number to use, someone with Java can predict stream IDs if they can guess the current seed
     */
    Random random = new SecureRandom();

    @Override
    public StreamID createStreamID() {
        return new BasicStreamID(new BigInteger( MAX_STRING_SIZE * 5, random ).toString( 36 ));
    }

    public static StreamID createStreamID(String name) {
        return new BasicStreamID(name);
    }

    private static class BasicStreamID implements StreamID, Cacheable {
        String id;

        public BasicStreamID(String id) {
            if ( id == null || id.isEmpty() ) {
                throw new IllegalArgumentException( "Argument 'id' cannot be null." );
            }
            this.id = StringEscapeUtils.escapeXml( id );
        }

        @Override
        public String getID() {
            return id;
        }

        @Override
		public String toString() {
            return id;
        }

        @Override
		public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o ) return true;
            if ( o == null || getClass() != o.getClass() ) return false;
            return id.equals( ((BasicStreamID) o).id );
        }

        @Override
        public int getCachedSize() throws CannotCalculateSizeException
        {
            // Approximate the size of the object in bytes by calculating the size of each field.
            int size = 0;
            size += CacheSizes.sizeOfObject();   // overhead of object
            size += CacheSizes.sizeOfString(id); // id
            return size;
        }
    }
}

/*
 * Copyright 2011 <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
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
package org.ocpsoft.rewrite.param;

import org.ocpsoft.rewrite.bind.Binding;

/**
 * Represents an object which may be parameterized and bound.
 * 
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 * 
 */
public interface Parameterized<C extends Parameterized<C, P, T>, P extends Parameter<P, T>, T>
{
   /**
    * Select the parameter with the given name.
    * <p>
    * See also: {@link #where(String, String)}, {@link #where(String, String, org.ocpsoft.rewrite.bind.Binding)}, {@link #where(String, org.ocpsoft.rewrite.bind.Binding)}
    */
   P where(String param);

   /**
    * Select the parameter with the given name. Bind the result of a successful parameter match to the given
    * {@link Binding}
    * <p>
    * See also: {@link #where(String, String)}, {@link #where(String, String, Binding)}, {@link #where(String, Binding)}
    */
   P where(String param, Binding binding);
}
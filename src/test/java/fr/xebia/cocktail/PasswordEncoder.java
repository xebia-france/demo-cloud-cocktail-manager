/*
 * Copyright 2008-2012 Xebia and the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.xebia.cocktail;

import org.springframework.security.authentication.encoding.LdapShaPasswordEncoder;

/**
 * Small util to {SHA} encode password for <code>src/main/webapp/WEB-INF/applicationContext.xml</code>.
 * 
 * @author <a href="mailto:cleclerc@xebia.fr">Cyrille Le Clerc</a>
 */
public class PasswordEncoder {

    public static void main(String[] args) {

        LdapShaPasswordEncoder passwordEncoder = new LdapShaPasswordEncoder();

        String pwd = passwordEncoder.encodePassword("foo", null);
        System.out.println(pwd);

    }
}

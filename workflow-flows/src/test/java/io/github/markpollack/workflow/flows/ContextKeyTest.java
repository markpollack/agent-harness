/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.flows;

import io.github.markpollack.workflow.core.ContextKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextKeyTest {

    @Test
    void ofShouldCreateKeyWithNameAndType() {
        ContextKey<String> key = ContextKey.of("myKey", String.class);

        assertThat(key.key()).isEqualTo("myKey");
        assertThat(key.type()).isEqualTo(String.class);
    }

    @Test
    void equalsShouldMatchOnKeyString() {
        ContextKey<String> key1 = ContextKey.of("same", String.class);
        ContextKey<String> key2 = ContextKey.of("same", String.class);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }

    @Test
    void equalsShouldNotMatchDifferentKeys() {
        ContextKey<String> key1 = ContextKey.of("a", String.class);
        ContextKey<String> key2 = ContextKey.of("b", String.class);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void ofShouldRejectNullKey() {
        assertThatThrownBy(() -> ContextKey.of(null, String.class))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofShouldRejectNullType() {
        assertThatThrownBy(() -> ContextKey.of("key", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringShouldIncludeKeyAndType() {
        ContextKey<Integer> key = ContextKey.of("count", Integer.class);

        assertThat(key.toString()).contains("count").contains("Integer");
    }
}

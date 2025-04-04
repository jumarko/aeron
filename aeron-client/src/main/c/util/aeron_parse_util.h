/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AERON_PARSE_UTIL_H
#define AERON_PARSE_UTIL_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#define AERON_MAX_HOST_LENGTH (384)
#define AERON_MAX_PORT_LENGTH (8)
#define AERON_MAX_PREFIX_LENGTH (8)

typedef struct aeron_parsed_address_stct
{
    char host[AERON_MAX_HOST_LENGTH];
    char port[AERON_MAX_PORT_LENGTH];
    int ip_version_hint;
}
aeron_parsed_address_t;

typedef struct aeron_parsed_interface_stct
{
    char host[AERON_MAX_HOST_LENGTH];
    char port[AERON_MAX_PORT_LENGTH];
    char prefix[AERON_MAX_PREFIX_LENGTH];
    int ip_version_hint;
}
aeron_parsed_interface_t;

int aeron_parse_size64(const char *str, uint64_t *result);

int aeron_parse_duration_ns(const char *str, uint64_t *result);

bool aeron_parse_bool(const char *str, bool def);

int aeron_address_split(const char *address_str, aeron_parsed_address_t *parsed_address);

int aeron_interface_split(const char *interface_str, aeron_parsed_interface_t *parsed_interface);

int aeron_parse_get_line(char *line, size_t max_length, const char *buffer);

void aeron_config_prop_warning(const char *name, const char *str);

uint64_t aeron_config_parse_uint64(const char *name, const char *str, uint64_t def, uint64_t min, uint64_t max);

int32_t aeron_config_parse_int32(const char *name, const char *str, int32_t def, int32_t min, int32_t max);

int64_t aeron_config_parse_int64(const char *name, const char *str, int64_t def, int64_t min, int64_t max);

uint32_t aeron_config_parse_uint32(const char *name, const char *str, uint32_t def, uint32_t min, uint32_t max);

uint64_t aeron_config_parse_size64(const char *name, const char *str, uint64_t def, uint64_t min, uint64_t max);

uint64_t aeron_config_parse_duration_ns(const char *name, const char *str, uint64_t def, uint64_t min, uint64_t max);

#endif

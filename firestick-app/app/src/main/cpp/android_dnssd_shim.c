/*
 * Android DNS-SD shim matching FDH2/UxPlay's dnssd.h contract.
 * Collects key/value TXT records for Android NsdManager advertisement and
 * formats wire-length-prefixed DNS-SD TXT blobs for BLE /info responses.
 */

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "android_dnssd_shim.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#define MAX_SERVNAME 256
#define MAX_TXT_ENTRIES 32
#define MAX_TXT_KEY 32
#define MAX_TXT_VAL 160
#define MAX_WIRE_TXT_BYTES 2048

typedef struct {
    char key[MAX_TXT_KEY];
    char val[MAX_TXT_VAL];
} txt_entry_t;

typedef struct {
    txt_entry_t entries[MAX_TXT_ENTRIES];
    int count;
    unsigned char wire[MAX_WIRE_TXT_BYTES];
    int wire_len;
} txt_record_t;

typedef struct {
    txt_record_t raop_record;
    txt_record_t airplay_record;
    char codec_cn[16];
    char raop_servname[MAX_SERVNAME];
    char *pk_copy;
} android_dnssd_priv_t;

static void txt_clear(txt_record_t *rec) {
    rec->count = 0;
    rec->wire_len = 0;
}

static void txt_set(txt_record_t *rec, const char *key, const char *val) {
    if (!key || !val) return;
    for (int i = 0; i < rec->count; i++) {
        if (strcmp(rec->entries[i].key, key) == 0) {
            snprintf(rec->entries[i].val, MAX_TXT_VAL, "%s", val);
            return;
        }
    }
    if (rec->count < MAX_TXT_ENTRIES) {
        snprintf(rec->entries[rec->count].key, MAX_TXT_KEY, "%s", key);
        snprintf(rec->entries[rec->count].val, MAX_TXT_VAL, "%s", val);
        rec->count++;
    }
}

static void txt_build_wire(txt_record_t *rec) {
    int pos = 0;
    for (int i = 0; i < rec->count; i++) {
        char line[MAX_TXT_KEY + MAX_TXT_VAL + 2];
        int len = snprintf(line, sizeof(line), "%s=%s", rec->entries[i].key, rec->entries[i].val);
        if (len <= 0 || len > 255) continue;
        if (pos + 1 + len > MAX_WIRE_TXT_BYTES) break;
        rec->wire[pos++] = (unsigned char) len;
        memcpy(rec->wire + pos, line, (size_t) len);
        pos += len;
    }
    rec->wire_len = pos;
}

dnssd_t *dnssd_init(const char *name, int name_len, const char *hw_addr, int hw_addr_len,
                    unsigned char pin_pw, int *error) {
    const char *dot_local = ".local";
    if (error) *error = DNSSD_ERROR_NOERROR;

    if (!name || !hw_addr || hw_addr_len <= 0 || name_len <= 0 ||
        *(name + name_len) != '\0' || name_len != (int) strlen(name) ||
        strcmp(name, dot_local) == 0) {
        if (error) *error = DNSSD_ERROR_BADNAME;
        return NULL;
    }

    const char *dot_local_start = strstr(name, dot_local);
    if (dot_local_start) {
        name_len = (int) (dot_local_start - name);
    }

    dnssd_t *dnssd = (dnssd_t *) calloc(1, sizeof(dnssd_t));
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) calloc(1, sizeof(android_dnssd_priv_t));
    if (!dnssd || !priv) {
        free(priv);
        free(dnssd);
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }

    dnssd->name_len = name_len;
    dnssd->name = (char *) calloc(1, (size_t) name_len + 1);
    dnssd->hw_addr_len = hw_addr_len;
    dnssd->hw_addr = (char *) calloc(1, (size_t) hw_addr_len);
    if (!dnssd->name || !dnssd->hw_addr) {
        free(dnssd->name);
        free(dnssd->hw_addr);
        free(priv);
        free(dnssd);
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }

    memcpy(dnssd->name, name, (size_t) name_len);
    memcpy(dnssd->hw_addr, hw_addr, (size_t) hw_addr_len);

    dnssd->pin_pw = pin_pw;
    dnssd->features1 = (uint32_t) strtoul(FEATURES_1, NULL, 16);
    dnssd->features2 = (uint32_t) strtoul(FEATURES_2, NULL, 16);
    dnssd->dnssd_private = priv;

    snprintf(priv->codec_cn, sizeof(priv->codec_cn), "%s", RAOP_CN);
    return dnssd;
}

void dnssd_destroy(dnssd_t *dnssd) {
    if (!dnssd) return;
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    if (priv) {
        free(priv->pk_copy);
        free(priv);
    }
    free(dnssd->name);
    free(dnssd->hw_addr);
    free(dnssd);
}

int dnssd_register_raop(dnssd_t *dnssd, unsigned short port) {
    (void) port;
    assert(dnssd);
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    if (!priv) return -1;

    char features[32] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    txt_record_t *rec = &priv->raop_record;
    txt_clear(rec);

    txt_set(rec, "ch", RAOP_CH);
    txt_set(rec, "cn", priv->codec_cn);
    txt_set(rec, "da", RAOP_DA);
    txt_set(rec, "et", RAOP_ET);
    txt_set(rec, "vv", RAOP_VV);
    txt_set(rec, "ft", features);
    txt_set(rec, "am", GLOBAL_MODEL);
    txt_set(rec, "md", RAOP_MD);
    txt_set(rec, "rhd", RAOP_RHD);

    switch (dnssd->pin_pw) {
    case 2:
    case 3:
        txt_set(rec, "pw", "true");
        txt_set(rec, "sf", "0x84");
        break;
    case 1:
        txt_set(rec, "pw", "true");
        txt_set(rec, "sf", "0x8c");
        break;
    default:
        txt_set(rec, "pw", "false");
        txt_set(rec, "sf", RAOP_SF);
        break;
    }

    txt_set(rec, "sr", RAOP_SR);
    txt_set(rec, "ss", RAOP_SS);
    txt_set(rec, "sv", RAOP_SV);
    txt_set(rec, "tp", RAOP_TP);
    txt_set(rec, "txtvers", RAOP_TXTVERS);
    txt_set(rec, "vs", RAOP_VS);
    txt_set(rec, "vn", RAOP_VN);
    if (dnssd->pk) {
        txt_set(rec, "pk", dnssd->pk);
    }
    txt_build_wire(rec);

    char hw_prefix[32] = {0};
    if (utils_hwaddr_raop(hw_prefix, sizeof(hw_prefix), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }
    snprintf(priv->raop_servname, sizeof(priv->raop_servname), "%s@%s", hw_prefix, dnssd->name);
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd, unsigned short port) {
    (void) port;
    assert(dnssd);
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    if (!priv) return -1;

    char device_id[3 * MAX_HWADDR_LEN] = {0};
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }

    char features[32] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    txt_record_t *rec = &priv->airplay_record;
    txt_clear(rec);

    txt_set(rec, "deviceid", device_id);
    txt_set(rec, "features", features);
    txt_set(rec, "pw", (dnssd->pin_pw >= 1 && dnssd->pin_pw <= 3) ? "true" : "false");
    txt_set(rec, "flags", AIRPLAY_FLAGS);
    txt_set(rec, "model", GLOBAL_MODEL);
    if (dnssd->pk) {
        txt_set(rec, "pk", dnssd->pk);
    }
    txt_set(rec, "pi", AIRPLAY_PI);
    txt_set(rec, "srcvers", AIRPLAY_SRCVERS);
    txt_set(rec, "vv", AIRPLAY_VV);
    txt_build_wire(rec);
    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd) {
    if (!dnssd || !dnssd->dnssd_private) return;
    txt_clear(&((android_dnssd_priv_t *) dnssd->dnssd_private)->raop_record);
}

void dnssd_unregister_airplay(dnssd_t *dnssd) {
    if (!dnssd || !dnssd->dnssd_private) return;
    txt_clear(&((android_dnssd_priv_t *) dnssd->dnssd_private)->airplay_record);
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    if (!dnssd || !dnssd->dnssd_private) {
        if (length) *length = 0;
        return NULL;
    }
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    if (length) *length = priv->raop_record.wire_len;
    return (const char *) priv->raop_record.wire;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    if (!dnssd || !dnssd->dnssd_private) {
        if (length) *length = 0;
        return NULL;
    }
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    if (length) *length = priv->airplay_record.wire_len;
    return (const char *) priv->airplay_record.wire;
}

const char *dnssd_get_name(dnssd_t *dnssd, int *length) {
    if (!dnssd) {
        if (length) *length = 0;
        return "";
    }
    if (length) *length = dnssd->name_len;
    return dnssd->name;
}

const char *dnssd_get_hw_addr(dnssd_t *dnssd, int *length) {
    if (!dnssd) {
        if (length) *length = 0;
        return NULL;
    }
    if (length) *length = dnssd->hw_addr_len;
    return dnssd->hw_addr;
}

uint64_t dnssd_get_airplay_features(dnssd_t *dnssd) {
    if (!dnssd) return 0;
    return (((uint64_t) dnssd->features2) << 32) | (uint64_t) dnssd->features1;
}

void dnssd_set_pk(dnssd_t *dnssd, char *pk_str) {
    if (!dnssd) return;
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    if (!priv) {
        dnssd->pk = pk_str;
        return;
    }
    free(priv->pk_copy);
    priv->pk_copy = pk_str ? strdup(pk_str) : NULL;
    dnssd->pk = priv->pk_copy;
}

void dnssd_set_airplay_features(dnssd_t *dnssd, int bit, int val) {
    if (!dnssd || bit < 0 || bit > 63 || val < 0 || val > 1) return;
    uint32_t mask = 0;
    uint32_t *target = NULL;
    if (bit >= 32) {
        mask = 0x1U << (bit - 32);
        target = &dnssd->features2;
    } else {
        mask = 0x1U << bit;
        target = &dnssd->features1;
    }
    if (val) {
        *target |= mask;
    } else {
        *target &= ~mask;
    }
}

int dnssd_get_service_fd(dnssd_t *dnssd, int service) {
    (void) dnssd;
    (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd, int service) {
    (void) dnssd;
    (void) service;
    return 0;
}

int android_dnssd_get_raop_txt_count(dnssd_t *dnssd) {
    if (!dnssd || !dnssd->dnssd_private) return 0;
    return ((android_dnssd_priv_t *) dnssd->dnssd_private)->raop_record.count;
}

const char *android_dnssd_get_raop_txt_key(dnssd_t *dnssd, int index) {
    if (!dnssd || !dnssd->dnssd_private) return NULL;
    txt_record_t *rec = &((android_dnssd_priv_t *) dnssd->dnssd_private)->raop_record;
    return (index >= 0 && index < rec->count) ? rec->entries[index].key : NULL;
}

const char *android_dnssd_get_raop_txt_val(dnssd_t *dnssd, int index) {
    if (!dnssd || !dnssd->dnssd_private) return NULL;
    txt_record_t *rec = &((android_dnssd_priv_t *) dnssd->dnssd_private)->raop_record;
    return (index >= 0 && index < rec->count) ? rec->entries[index].val : NULL;
}

int android_dnssd_get_airplay_txt_count(dnssd_t *dnssd) {
    if (!dnssd || !dnssd->dnssd_private) return 0;
    return ((android_dnssd_priv_t *) dnssd->dnssd_private)->airplay_record.count;
}

const char *android_dnssd_get_airplay_txt_key(dnssd_t *dnssd, int index) {
    if (!dnssd || !dnssd->dnssd_private) return NULL;
    txt_record_t *rec = &((android_dnssd_priv_t *) dnssd->dnssd_private)->airplay_record;
    return (index >= 0 && index < rec->count) ? rec->entries[index].key : NULL;
}

const char *android_dnssd_get_airplay_txt_val(dnssd_t *dnssd, int index) {
    if (!dnssd || !dnssd->dnssd_private) return NULL;
    txt_record_t *rec = &((android_dnssd_priv_t *) dnssd->dnssd_private)->airplay_record;
    return (index >= 0 && index < rec->count) ? rec->entries[index].val : NULL;
}

const char *android_dnssd_get_raop_servname(dnssd_t *dnssd) {
    if (!dnssd || !dnssd->dnssd_private) return NULL;
    return ((android_dnssd_priv_t *) dnssd->dnssd_private)->raop_servname;
}

void android_dnssd_set_codecs(dnssd_t *dnssd, int alac, int aac) {
    if (!dnssd || !dnssd->dnssd_private) return;
    android_dnssd_priv_t *priv = (android_dnssd_priv_t *) dnssd->dnssd_private;
    char buf[16] = {0};
    int pos = snprintf(buf, sizeof(buf), "0");
    if (alac && pos > 0 && pos < (int) sizeof(buf)) {
        pos += snprintf(buf + pos, sizeof(buf) - pos, ",1");
    }
    if (aac && pos > 0 && pos < (int) sizeof(buf)) {
        snprintf(buf + pos, sizeof(buf) - pos, ",2,3");
    }
    snprintf(priv->codec_cn, sizeof(priv->codec_cn), "%s", buf);
}

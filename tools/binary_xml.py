"""Narrow Android binary-XML edits. Preserve class names and all unedited resources."""
import struct

ANDROID='http://schemas.android.com/apk/res/android'
OLD='com.google.android.inputmethod.latin'
NEW='org.gboardopenai.keyboard'
U32=lambda b,p:struct.unpack_from('<I',b,p)[0]

def string_pool(data):
    _,header,size,count,styles,flags,start,style_start=struct.unpack_from('<HHIIIIII',data,0)
    strings=[]
    def n8(p):
        v=data[p];return (((v&127)<<8)|data[p+1],p+2) if v&128 else (v,p+1)
    for i in range(count):
        p=start+U32(data,header+i*4)
        if flags&256:
            _,p=n8(p);length,p=n8(p);s=data[p:p+length].replace(b'\xc0\x80',b'\0').decode('utf-8','surrogatepass')
        else:
            length=struct.unpack_from('<H',data,p)[0];p+=2
            if length&32768:length=((length&32767)<<16)|struct.unpack_from('<H',data,p)[0];p+=2
            s=data[p:p+length*2].decode('utf-16le','surrogatepass')
        strings.append(s)
    style_offsets=data[header+count*4:header+(count+styles)*4]
    style_data=data[style_start:] if style_start else b''
    def encode(values):
        offsets=[];body=bytearray()
        def e8(n):return bytes([n]) if n<128 else bytes([128|(n>>8),n&255])
        for s in values:
            offsets.append(len(body))
            raw=s.encode('utf-8' if flags&256 else 'utf-16le','surrogatepass');length=len(s.encode('utf-16le','surrogatepass'))//2
            if flags&256:raw=raw.replace(b'\0',b'\xc0\x80')
            if flags&256:body.extend(e8(length)+e8(len(raw))+raw+b'\0')
            else:body.extend((struct.pack('<H',length) if length<32768 else struct.pack('<HH',32768|(length>>16),length&65535))+raw+b'\0\0')
        body.extend(b'\0'*(-len(body)%4));new_start=28+4*len(values)+len(style_offsets)
        new_style_start=new_start+len(body) if styles else 0
        size=new_start+len(body)+len(style_data)
        return struct.pack('<HHIIIIII',1,28,size,len(values),styles,flags&~1,new_start,new_style_start)+struct.pack('<'+'I'*len(offsets),*offsets)+style_offsets+body+style_data
    return strings,encode

def patch(data,add_settings=False,accessibility_resource=None):
    if data[:2]!=b'\x03\x00':return data
    if not add_settings and accessibility_resource is None and OLD.encode() not in data and OLD.encode('utf-16le') not in data:return data
    chunks=[];p=8
    while p<len(data):
        size=U32(data,p+4);chunks.append(bytearray(data[p:p+size]));p+=size
    pool_index=next(i for i,c in enumerate(chunks) if struct.unpack_from('<H',c)[0]==1)
    strings,encode=string_pool(chunks[pool_index]);changed=False;result=[];stack=[];attribute_ids={}
    def index(s):
        if s not in strings:strings.append(s)
        return strings.index(s)
    def attrs(c):
        start,size,count=struct.unpack_from('<HHH',c,24)
        for i in range(count):yield 16+start+size*i
    # Explicit construction avoids ushort truncation of the element's name index.
    def activity():
        values=[]
        for name,value,typ in [('name','org.gboardopenai.VoiceSettingsActivity',3),('exported',1,0x12),('label','OpenAI dictation',3)]:
            v=index(value) if typ==3 else value
            values.append(struct.pack('<IIIHBBI',index(ANDROID),index(name),v if typ==3 else 0xffffffff,8,0,typ,v))
        start=struct.pack('<HHIII',0x102,16,36+20*len(values),0,0xffffffff)+struct.pack('<IIHHHHHH',0xffffffff,index('activity'),20,20,len(values),0,0,0)+b''.join(values)
        end=struct.pack('<HHIIIII',0x103,16,24,0,0xffffffff,0xffffffff,index('activity'))
        return [start,end]
    def service():
        ids={'name':0x01010003,'label':0x01010001,'permission':0x01010006,
             'exported':0x01010010,'resource':0x01010025}
        def start(name,attrs=()):
            values=[]
            for attr,value,kind in sorted(attrs,key=lambda item:ids[item[0]]):
                a=index(attr);attribute_ids[a]=ids[attr]
                v=index(value) if kind==3 else value
                values.append(struct.pack('<IIIHBBI',index(ANDROID),a,v if kind==3 else 0xffffffff,8,0,kind,v))
            return struct.pack('<HHIII',0x102,16,36+20*len(values),0,0xffffffff)+struct.pack('<IIHHHHHH',0xffffffff,index(name),20,20,len(values),0,0,0)+b''.join(values)
        def end(name):return struct.pack('<HHIIIII',0x103,16,24,0,0xffffffff,0xffffffff,index(name))
        return [start('service',[('name','org.gboardopenai.MessagesAccessibilityService',3),
                ('label','Messages dictation shortcut',3),('permission','android.permission.BIND_ACCESSIBILITY_SERVICE',3),('exported',1,0x12)]),
            start('intent-filter'),start('action',[('name','android.accessibilityservice.AccessibilityService',3)]),end('action'),end('intent-filter'),
            start('meta-data',[('name','android.accessibilityservice',3),('resource',accessibility_resource,1)]),end('meta-data'),end('service')]
    for c in chunks:
        typ=struct.unpack_from('<H',c)[0]
        if typ==0x102:
            name=strings[U32(c,20)];stack.append(name)
            for at in attrs(c):
                attr=strings[U32(c,at+4)];kind=c[at+15];value=U32(c,at+16)
                old=strings[value] if kind==3 else None;new=None
                if old is not None:
                    if name=='manifest' and attr=='package' and old==OLD:new=NEW
                    if attr in ('authorities','targetPackage','permission') and OLD in old:new=old.replace(OLD,NEW)
                    if name in ('permission','uses-permission','meta-data') and attr=='name' and OLD in old:new=old.replace(OLD,NEW)
                    if name=='data' and attr=='host' and OLD in old:new=old.replace(OLD,NEW)
                if new is not None:
                    n=index(new);struct.pack_into('<I',c,at+8,n);struct.pack_into('<I',c,at+16,n);changed=True
                if name=='application' and attr=='allowBackup':
                    struct.pack_into('<I',c,at+8,0xffffffff);c[at+15]=0x12;struct.pack_into('<I',c,at+16,0);changed=True
                if name=='application' and attr=='label':
                    n=index('Gboard + OpenAI');struct.pack_into('<I',c,at+8,n);c[at+15]=3;struct.pack_into('<I',c,at+16,n);changed=True
        if typ==0x103:
            name=strings[U32(c,20)]
            if add_settings and name=='application':result.extend(activity());changed=True
            if accessibility_resource is not None and name=='application':result.extend(service());changed=True
            if stack:stack.pop()
        result.append(c)
    if not changed:return data
    result[pool_index]=encode(strings)
    if attribute_ids:
        map_index=next((i for i,c in enumerate(result) if struct.unpack_from('<H',c)[0]==0x180),None)
        values=list(struct.unpack('<'+'I'*((len(result[map_index])-8)//4),result[map_index][8:])) if map_index is not None else []
        values.extend([0]*max(0,max(attribute_ids)+1-len(values)))
        for i,value in attribute_ids.items():
            if values[i] not in (0,value):raise ValueError('Conflicting Android attribute resource ID')
            values[i]=value
        resource_map=struct.pack('<HHI',0x180,8,8+4*len(values))+struct.pack('<'+'I'*len(values),*values)
        if map_index is None:result.insert(pool_index+1,resource_map)
        else:result[map_index]=resource_map
    body=b''.join(result)
    return struct.pack('<HHI',3,8,len(body)+8)+body

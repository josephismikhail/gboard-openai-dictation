import struct,unittest
from tools.binary_xml import patch,string_pool,OLD,NEW,ANDROID

def fixture(utf8=False):
    strings=['manifest','package',OLD,'application','name','provider','authorities',OLD+'.files','com.android.inputmethod.latin.LatinIME','label','allowBackup',ANDROID,'Gboard','activity','exported','emoji \ud83d\ude42']
    body=bytearray();offsets=[]
    for s in strings:
        offsets.append(len(body));raw=s.encode('utf-8' if utf8 else 'utf-16le','surrogatepass')
        if utf8:body+=bytes([len(s),len(raw)])+raw+b'\0'
        else:body+=struct.pack('<H',len(raw)//2)+raw+b'\0\0'
    body+=b'\0'*(-len(body)%4)
    start=28+4*len(strings)
    pool=struct.pack('<HHIIIIII',1,28,start+len(body),len(strings),0,256 if utf8 else 0,start,0)+struct.pack('<'+'I'*len(strings),*offsets)+body
    def tag(name,attrs=(),end=False):
        if end:return struct.pack('<HHIIIII',0x103,16,24,0,0xffffffff,0xffffffff,strings.index(name))
        values=[]
        for attr,value,typ in attrs:
            n=strings.index(value) if typ==3 else value
            values.append(struct.pack('<IIIHBBI',strings.index(ANDROID),strings.index(attr),n if typ==3 else 0xffffffff,8,0,typ,n))
        return struct.pack('<HHIII',0x102,16,36+20*len(values),0,0xffffffff)+struct.pack('<IIHHHHHH',0xffffffff,strings.index(name),20,20,len(values),0,0,0)+b''.join(values)
    chunks=[pool,tag('manifest',[('package',OLD,3)]),tag('application',[('label','Gboard',3),('allowBackup',1,0x12)]),tag('provider',[('name','com.android.inputmethod.latin.LatinIME',3),('authorities',OLD+'.files',3)]),tag('provider',end=True),tag('application',end=True),tag('manifest',end=True)]
    raw=b''.join(chunks);return struct.pack('<HHI',3,8,len(raw)+8)+raw

def inspect(data):
    p=8;strings=[];tags=[]
    while p<len(data):
        kind,header,size=struct.unpack_from('<HHI',data,p);chunk=data[p:p+size]
        if kind==1:strings,_=string_pool(chunk)
        if kind==0x102:
            name=strings[struct.unpack_from('<I',chunk,20)[0]];attrs={}
            start,step,count=struct.unpack_from('<HHH',chunk,24)
            for i in range(count):
                at=16+start+i*step;n=strings[struct.unpack_from('<I',chunk,at+4)[0]];t=chunk[at+15];v=struct.unpack_from('<I',chunk,at+16)[0]
                attrs[n]=strings[v] if t==3 else v
            tags.append((name,attrs))
        p+=size
    return strings,tags

class BinaryXmlTests(unittest.TestCase):
    def test_accessibility_service_has_typed_metadata_and_protected_binding(self):
        data=patch(fixture(),True,0x7f17124b)
        _,tags=inspect(data)
        service=next(a for name,a in tags if name=='service')
        self.assertEqual('org.gboardopenai.MessagesAccessibilityService',service['name'])
        self.assertEqual('android.permission.BIND_ACCESSIBILITY_SERVICE',service['permission'])
        self.assertEqual('Messages dictation shortcut',service['label'])
        self.assertEqual(1,service['exported'])
        metadata=next(a for name,a in tags if name=='meta-data')
        self.assertEqual('android.accessibilityservice',metadata['name'])
        self.assertEqual(0x7f17124b,metadata['resource'])
        strings,_=inspect(data);p=8;found=False
        while p<len(data):
            kind,_,size=struct.unpack_from('<HHI',data,p)
            if kind==0x180:
                values=struct.unpack_from('<'+'I'*((size-8)//4),data,p+8)
                self.assertEqual(0x01010025,values[strings.index('resource')]);found=True
            p+=size
        self.assertTrue(found,'Metadata attribute needs an Android resource-map entry')
    def test_package_and_authorities_change_without_renaming_classes(self):
        _,tags=inspect(patch(fixture(),True));d=dict(tags)
        self.assertEqual(NEW,d['manifest']['package'])
        self.assertEqual(NEW+'.files',d['provider']['authorities'])
        self.assertEqual('com.android.inputmethod.latin.LatinIME',d['provider']['name'])
        self.assertEqual('org.gboardopenai.VoiceSettingsActivity',d['activity']['name'])
        self.assertEqual(0,d['application']['allowBackup'])
    def test_modified_utf8_surrogate_strings_survive(self):
        before,_=inspect(fixture(True));after,_=inspect(patch(fixture(True),True))
        self.assertEqual(before[-1],after[len(before)-1])
    def test_non_binary_and_unrelated_resources_are_identical(self):
        for data in [b'<xml>plain</xml>',b'\x03\x00\x08\x00no relevant package']:
            self.assertIs(data,patch(data))

if __name__=='__main__':unittest.main()
